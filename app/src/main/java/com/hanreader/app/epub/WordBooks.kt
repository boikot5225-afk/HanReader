package com.hanreader.app.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** A paragraph of a word-processor document; [heading] when its style is a heading or title. */
internal data class DocParagraph(val text: String, val heading: Boolean, val image: String? = null)

/**
 * Chapters from document paragraphs: a new chapter at each heading paragraph; with fewer than
 * two headings, the TXT rules (第…章 lines, else parts of a fixed size) apply.
 */
internal class ParagraphBook(
    override val title: String,
    override val author: String,
    paragraphs: List<DocParagraph>,
    private val image: (String) -> ByteArray?
) : BookSource {
    private val parts: List<Pair<String, List<DocParagraph>>>
    override val chapters: List<EpubChapter>
    override val toc: List<TocEntry>

    init {
        val list = paragraphs.filter { it.text.isNotBlank() || it.image != null }
        val headings = list.count { it.heading && it.text.isNotBlank() }
        parts = if (headings >= 2) {
            val out = ArrayList<Pair<String, MutableList<DocParagraph>>>()
            for (p in list) {
                if (p.heading && p.text.isNotBlank()) out += p.text.trim() to mutableListOf(p)
                else {
                    if (out.isEmpty()) out += "Начало" to mutableListOf()
                    out.last().second += p
                }
            }
            out.filter { it.second.isNotEmpty() }
        } else {
            // No styled headings: fall back to the plain-text rules on the text paragraphs.
            val txt = TxtBook(list.filter { it.image == null }.joinToString("\n") { it.text }.toByteArray(), title)
            txt.chapters.map { c -> c.title to txt.blocks(c.index).map { b -> DocParagraph((b as EpubBlock.Text).text, b.heading) } }
        }
        chapters = parts.mapIndexed { i, (t, _) -> EpubChapter(i, "doc:$i", t) }
        toc = chapters.map { TocEntry(it.title, it.index, 0) }
    }

    override fun blocks(chapterIndex: Int): List<EpubBlock> = parts[chapterIndex].second.map { p ->
        if (p.image != null) EpubBlock.Image(p.image) else EpubBlock.Text(p.text.trim(), p.heading)
    }

    override fun bytes(path: String): ByteArray? = image(path)
    override fun cover(): ByteArray? = null
    override fun close() = Unit
}

// ------------------------------------------------------------------ DOCX

/** Word 2007+ (.docx): word/document.xml paragraphs, heading styles, images, docProps/core.xml metadata. */
object DocxBook {
    fun open(file: File, fallbackTitle: String): BookSource {
        val zip = ZipFile(file)
        try {
            fun text(name: String) = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }
            val doc = Jsoup.parse(text("word/document.xml") ?: throw EpubException("В DOCX нет текста"), "", Parser.xmlParser())
            val styles = text("word/styles.xml")?.let { Jsoup.parse(it, "", Parser.xmlParser()) }
            // Style id -> is it a heading/title (by id, by name, or by outline level).
            val headingStyles = HashSet<String>()
            styles?.select("w|style")?.forEach { st ->
                val id = st.attr("w:styleId")
                val name = st.selectFirst("w|name")?.attr("w:val").orEmpty()
                val outline = st.selectFirst("w|outlineLvl") != null
                if (outline || isHeadingName(id) || isHeadingName(name)) headingStyles += id
            }
            val rels = HashMap<String, String>()
            text("word/_rels/document.xml.rels")?.let { r ->
                Jsoup.parse(r, "", Parser.xmlParser()).select("Relationship").forEach { rel ->
                    rels[rel.attr("Id")] = EpubBook.resolve("word", rel.attr("Target"))
                }
            }
            val paragraphs = ArrayList<DocParagraph>()
            for (p in doc.select("w|body w|p")) {
                val style = p.selectFirst("w|pPr > w|pStyle")?.attr("w:val").orEmpty()
                val heading = style in headingStyles || isHeadingName(style) || p.selectFirst("w|pPr > w|outlineLvl") != null
                val sb = StringBuilder()
                for (el in p.select("w|t, w|tab, w|br, a|blip")) {
                    when (el.tagName()) {
                        "w:t" -> sb.append(el.text())
                        "w:tab" -> sb.append(' ')
                        "w:br" -> sb.append('\n')
                        "a:blip" -> rels[el.attr("r:embed")]?.let { path ->
                            if (sb.isNotBlank()) { paragraphs += DocParagraph(sb.toString(), heading); sb.setLength(0) }
                            paragraphs += DocParagraph("", false, image = path)
                        }
                    }
                }
                paragraphs += DocParagraph(sb.toString(), heading)
            }
            val core = text("docProps/core.xml")?.let { Jsoup.parse(it, "", Parser.xmlParser()) }
            val title = core?.selectFirst("dc|title")?.text()?.trim().orEmpty().ifBlank { fallbackTitle }
            val author = core?.selectFirst("dc|creator")?.text()?.trim().orEmpty()
            val images = rels.values.toSet()
            return object : BookSource by ParagraphBook(title, author, paragraphs, { path ->
                if (path in images) zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } else null
            }) {
                override fun close() = zip.close()
            }
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    private fun isHeadingName(s: String): Boolean {
        val l = s.lowercase()
        return l.startsWith("heading") || l == "title" || l.startsWith("заголовок") || l.startsWith("标题") || l == "название"
    }
}

// ------------------------------------------------------------------ DOC (Word 97-2003)

/**
 * Word 97–2003 (.doc): the text is read straight from the binary file. The file is an OLE
 * compound document; the WordDocument stream holds the text, and the piece table (Clx) in the
 * 0Table/1Table stream says where each run of text is and whether it is 8-bit or UTF-16.
 * Only the main text is taken (no headers, footnotes, formatting or images).
 */
object DocBook {
    private val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    fun isCompoundFile(head: ByteArray) = head.size >= 8 && (0 until 8).all { head[it] == SIGNATURE[it] }

    fun open(file: File, fallbackTitle: String): BookSource {
        val text = extractText(file.readBytes())
        val paragraphs = text.split('\r', '\u000C', '\u0007').map { DocParagraph(it.replace('\u000B', '\n'), heading = false) }
        return ParagraphBook(fallbackTitle, "", paragraphs) { null }
    }

    fun extractText(bytes: ByteArray): String {
        val cfb = CompoundFile(bytes)
        val word = cfb.stream("WordDocument") ?: throw EpubException("Это не документ Word (нет WordDocument)")
        val w = ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN)
        if ((w.getShort(0).toInt() and 0xFFFF) != 0xA5EC) throw EpubException("Документ Word повреждён")
        val flags = w.getShort(0x0A).toInt() and 0xFFFF
        if (flags and 0x0100 != 0) throw EpubException("Документ Word зашифрован")
        val table = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw EpubException("Документ Word повреждён (нет таблицы)")
        var pos = 32
        val csw = w.getShort(pos).toInt() and 0xFFFF; pos += 2 + csw * 2
        val cslw = w.getShort(pos).toInt() and 0xFFFF
        val ccpText = w.getInt(pos + 2 + 3 * 4)
        pos += 2 + cslw * 4
        pos += 2 // cbRgFcLcb
        val fcClx = w.getInt(pos + 33 * 8)
        val lcbClx = w.getInt(pos + 33 * 8 + 4)
        if (lcbClx <= 0 || fcClx < 0 || fcClx + lcbClx > table.size) throw EpubException("Документ Word повреждён (нет таблицы фрагментов)")
        val t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        var i = fcClx
        while (i < fcClx + lcbClx && table[i].toInt() == 0x01) i += 3 + (t.getShort(i + 1).toInt() and 0xFFFF) // Prc
        if (table[i].toInt() != 0x02) throw EpubException("Документ Word повреждён (таблица фрагментов)")
        val lcb = t.getInt(i + 1)
        val plc = i + 5
        val n = (lcb - 4) / 12
        val cp1252 = Charset.forName("windows-1252")
        val out = StringBuilder()
        for (k in 0 until n) {
            val cpStart = t.getInt(plc + k * 4)
            if (cpStart >= ccpText) break
            val cpEnd = minOf(t.getInt(plc + (k + 1) * 4), ccpText)
            val len = cpEnd - cpStart
            val fcRaw = t.getInt(plc + (n + 1) * 4 + k * 8 + 2)
            val compressed = fcRaw and 0x40000000 != 0
            val fc = fcRaw and 0x3FFFFFFF
            if (compressed) {
                val off = fc / 2
                if (off + len <= word.size) out.append(String(word, off, len, cp1252))
            } else if (fc + len * 2 <= word.size) {
                out.append(String(word, fc, len * 2, Charsets.UTF_16LE))
            }
        }
        return cleanFields(out.toString())
    }

    /** Drops field codes (between 0x13 and 0x14), keeps field results, removes object markers. */
    private fun cleanFields(s: String): String {
        val sb = StringBuilder(s.length)
        var depth = 0
        var inCode = false
        for (c in s) {
            when (c) {
                '\u0013' -> { depth++; inCode = true }
                '\u0014' -> inCode = false
                '\u0015' -> { if (depth > 0) depth--; inCode = false }
                '\u0001', '\u0008', '\u001F' -> Unit
                '\u001E' -> if (!inCode) sb.append('-')
                else -> if (!inCode) sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Minimal reader for OLE compound files (MS-CFB): sector chains, directory, mini stream. */
    private class CompoundFile(private val b: ByteArray) {
        private val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        private val sectorSize = 1 shl (buf.getShort(0x1E).toInt() and 0xFFFF)
        private val miniSize = 1 shl (buf.getShort(0x20).toInt() and 0xFFFF)
        private val cutoff = buf.getInt(0x38)
        private val fat: IntArray
        private val miniFat: IntArray
        private val entries: List<Triple<String, Int, Long>> // name, start sector, size
        private val miniStream: ByteArray

        init {
            if (!isCompoundFile(b)) throw EpubException("Это не документ Word")
            val fatSectors = ArrayList<Int>()
            for (k in 0 until 109) { val s = buf.getInt(0x4C + k * 4); if (s >= 0) fatSectors += s }
            var difat = buf.getInt(0x44)
            var guard = 0
            while (difat >= 0 && guard++ < 10_000) {
                val base = offset(difat)
                for (k in 0 until sectorSize / 4 - 1) { val s = buf.getInt(base + k * 4); if (s >= 0) fatSectors += s }
                difat = buf.getInt(base + sectorSize - 4)
            }
            fat = IntArray(fatSectors.size * sectorSize / 4)
            for ((k, s) in fatSectors.withIndex()) for (j in 0 until sectorSize / 4) {
                val at = offset(s) + j * 4
                fat[k * sectorSize / 4 + j] = if (at + 4 <= b.size) buf.getInt(at) else -1
            }
            val dir = chain(buf.getInt(0x30))
            val list = ArrayList<Triple<String, Int, Long>>()
            var k = 0
            while (k + 128 <= dir.size) {
                val d = ByteBuffer.wrap(dir, k, 128).order(ByteOrder.LITTLE_ENDIAN)
                val nameLen = d.getShort(k + 64).toInt() and 0xFFFF
                val name = if (nameLen >= 2) String(dir, k, nameLen - 2, Charsets.UTF_16LE) else ""
                list += Triple(name, d.getInt(k + 116), d.getInt(k + 120).toLong() and 0xFFFFFFFFL)
                k += 128
            }
            entries = list
            val root = list.firstOrNull()
            miniStream = root?.let { chain(it.second).copyOf(it.third.toInt().coerceAtMost(Int.MAX_VALUE)) } ?: ByteArray(0)
            val mf = chain(buf.getInt(0x3C))
            val mb = ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN)
            miniFat = IntArray(mf.size / 4) { mb.getInt(it * 4) }
        }

        private fun offset(sector: Int) = (sector + 1) * sectorSize

        private fun chain(start: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s >= 0 && s < fat.size && guard++ < 1_000_000) {
                val o = offset(s)
                if (o + sectorSize > b.size) { if (o < b.size) out.write(b, o, b.size - o); break }
                out.write(b, o, sectorSize)
                s = fat[s]
            }
            return out.toByteArray()
        }

        private fun miniChain(start: Int, size: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s >= 0 && s < miniFat.size && guard++ < 1_000_000 && out.size() < size) {
                val o = s * miniSize
                if (o + miniSize > miniStream.size) break
                out.write(miniStream, o, miniSize)
                s = miniFat[s]
            }
            return out.toByteArray().copyOf(size)
        }

        fun stream(name: String): ByteArray? {
            val e = entries.drop(1).firstOrNull { it.first == name } ?: return null
            val size = e.third.toInt()
            return if (size < cutoff) miniChain(e.second, size) else chain(e.second).copyOf(size)
        }
    }
}
