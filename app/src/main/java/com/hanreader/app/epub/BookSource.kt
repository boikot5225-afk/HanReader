package com.hanreader.app.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.ZipFile

/** A book in any supported format: metadata, chapters in reading order, contents, images. */
interface BookSource : Closeable {
    val title: String
    val author: String
    val chapters: List<EpubChapter>
    val toc: List<TocEntry>
    fun blocks(chapterIndex: Int): List<EpubBlock>
    fun bytes(path: String): ByteArray?
    /** The cover image, or null (the library then draws one). */
    fun cover(): ByteArray?
}

enum class BookFormat(val extension: String) { EPUB("epub"), FB2("fb2"), FB2_ZIP("fb2.zip"), TXT("txt"), DOCX("docx"), DOC("doc") }

/**
 * Size limits that keep a huge or malicious file from exhausting the app's memory: the phone gives
 * an app a few hundred megabytes, and a plain-text book is read whole. A zip entry may declare a
 * false size, so entries are counted while they are read.
 */
object BookLimits {
    /** Any book file (EPUB and DOCX may carry many images). */
    const val MAX_FILE = 200L * 1024 * 1024
    /** A book read into memory whole: TXT, FB2, DOC, the FB2 inside a zip. ~30 million characters. */
    const val MAX_TEXT = 60L * 1024 * 1024
    /** One file inside an EPUB or DOCX (a chapter, an image). */
    const val MAX_ENTRY = 40L * 1024 * 1024

    fun mb(bytes: Long) = (bytes + 1024 * 1024 - 1) / (1024 * 1024)

    /** Reads [input] whole, failing with a readable message past [max] bytes. */
    fun read(input: java.io.InputStream, max: Long, what: String = "Файл"): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw EpubException("$what слишком большой: больше ${mb(max)} МБ")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Copies [input] to [out], failing past [MAX_FILE] bytes. */
    fun copy(input: java.io.InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_FILE) throw EpubException("Файл слишком большой: больше ${mb(MAX_FILE)} МБ")
            out.write(buf, 0, n)
        }
    }

    /** A whole-file book larger than [MAX_TEXT] is refused before it is read. */
    fun checkText(file: File) {
        if (file.length() > MAX_TEXT) throw EpubException("Книга слишком большая: ${mb(file.length())} МБ, можно до ${mb(MAX_TEXT)} МБ. Разделите её на части")
    }
}

object BookFiles {
    /** Recognises the format from the file's content: a name from Telegram or a file manager may lie. */
    fun sniff(file: File): BookFormat? {
        val head = file.inputStream().use { s -> ByteArray(4096).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return runCatching {
                ZipFile(file).use { z ->
                    when {
                        z.getEntry("META-INF/container.xml") != null -> BookFormat.EPUB
                        z.getEntry("word/document.xml") != null -> BookFormat.DOCX
                        z.entries().asSequence().any { it.name.endsWith(".fb2", ignoreCase = true) } -> BookFormat.FB2_ZIP
                        else -> null
                    }
                }
            }.getOrNull()
        }
        if (DocBook.isCompoundFile(head)) return BookFormat.DOC
        val start = String(head, Charsets.ISO_8859_1)
        if (start.contains("<FictionBook")) return BookFormat.FB2
        if (head.isEmpty()) return null
        // Plain text: anything without NUL bytes outside UTF-16 is accepted.
        val utf16 = head.size >= 2 && ((head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) || (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()))
        if (!utf16 && head.any { it == 0.toByte() }) return null
        return BookFormat.TXT
    }

    fun open(file: File, format: BookFormat): BookSource = when (format) {
        BookFormat.EPUB -> EpubBook(file)
        BookFormat.FB2 -> { BookLimits.checkText(file); Fb2Book(file.readBytes(), file.nameWithoutExtension) }
        BookFormat.FB2_ZIP -> ZipFile(file).use { z ->
            val e = z.entries().asSequence().first { it.name.endsWith(".fb2", ignoreCase = true) }
            Fb2Book(z.getInputStream(e).use { BookLimits.read(it, BookLimits.MAX_TEXT, "Текст книги") }, file.name.substringBefore('.'))
        }
        BookFormat.TXT -> { BookLimits.checkText(file); TxtBook(file.readBytes(), file.nameWithoutExtension) }
        BookFormat.DOCX -> DocxBook.open(file, file.nameWithoutExtension)
        BookFormat.DOC -> DocBook.open(file, file.nameWithoutExtension)
    }

    /** Opens a stored book; the format is in its file name (book-123.fb2.zip). */
    fun open(file: File): BookSource {
        val name = file.name.lowercase()
        val format = BookFormat.entries.sortedByDescending { it.extension.length }.firstOrNull { name.endsWith("." + it.extension) }
            ?: sniff(file) ?: throw EpubException("Неизвестный формат книги")
        return open(file, format)
    }
}

// ------------------------------------------------------------------ TXT

/**
 * A plain-text book. The encoding is detected (UTF-8 / UTF-16 with BOM, else UTF-8 if valid,
 * else GB18030, the usual one for Chinese .txt). Chapters start at heading lines such as
 * 第十二章 …, 第3回, 序章, Chapter 4; without headings the text is cut into parts.
 */
class TxtBook(bytes: ByteArray, name: String) : BookSource {
    override val title: String = name.ifBlank { "Без названия" }
    override val author: String = ""
    private val parts: List<Pair<String, List<String>>>
    override val chapters: List<EpubChapter>
    override val toc: List<TocEntry>

    init {
        val text = decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.lines().map { it.trim(' ', '\t', '　', ' ', '﻿') }.filter { it.isNotEmpty() }
        val found = ArrayList<Pair<String, MutableList<String>>>()
        var current: Pair<String, MutableList<String>>? = null
        for (l in lines) {
            if (l.length <= 40 && HEADING.matches(l)) {
                current = l to mutableListOf(l)
                found += current
            } else {
                if (current == null) { current = "Начало" to mutableListOf(); found += current }
                current.second += l
            }
        }
        parts = if (found.size >= 2) found.filter { it.second.isNotEmpty() } else splitBySize(lines)
        chapters = parts.mapIndexed { i, (t, _) -> EpubChapter(i, "txt:$i", t) }
        toc = chapters.map { TocEntry(it.title, it.index, 0) }
    }

    private fun splitBySize(lines: List<String>): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        var acc = ArrayList<String>()
        var size = 0
        for (l in lines) {
            acc += l; size += l.length
            if (size >= PART_CHARS) { out += "Часть ${out.size + 1}" to acc; acc = ArrayList(); size = 0 }
        }
        if (acc.isNotEmpty() || out.isEmpty()) out += "Часть ${out.size + 1}" to acc
        return out
    }

    override fun blocks(chapterIndex: Int): List<EpubBlock> {
        val (heading, lines) = parts[chapterIndex]
        return lines.mapIndexed { i, l -> EpubBlock.Text(l, heading = i == 0 && l == heading && heading != "Начало") }
    }

    override fun bytes(path: String): ByteArray? = null
    override fun cover(): ByteArray? = null
    override fun close() = Unit

    companion object {
        private const val PART_CHARS = 6_000
        private val HEADING = Regex(
            "^(第[0-9０-９零〇一二两三四五六七八九十百千万]+[章节回卷集部篇]|序章|序言|楔子|尾声|后记|番外|引子|Chapter\\s+\\d+|Глава\\s+\\d+).{0,30}$",
            RegexOption.IGNORE_CASE
        )

        fun decode(b: ByteArray): String {
            if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
            if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
            if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
            strict(b, Charsets.UTF_8)?.let { return it }
            return String(b, Charset.forName("GB18030"))
        }

        private fun strict(b: ByteArray, cs: Charset): String? = try {
            cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) { null }
    }
}

// ------------------------------------------------------------------ FB2

/**
 * FictionBook 2: metadata from <title-info>, chapters = the first-level <section>s of the main
 * <body> (a section holding only sub-sections is split into them), images from <binary>.
 */
class Fb2Book(bytes: ByteArray, name: String) : BookSource {
    private val doc = Jsoup.parse(TxtBook.decode(bytes).let { stripDeclaredEncoding(it) }, "", Parser.xmlParser())
    private val sections: List<Element>
    private val binaries: Map<String, Element>
    override val title: String
    override val author: String
    override val chapters: List<EpubChapter>
    override val toc: List<TocEntry>

    init {
        val info = doc.selectFirst("description > title-info")
        title = info?.selectFirst("book-title")?.text()?.trim().orEmpty().ifBlank { name }
        author = info?.select("author")?.firstOrNull()?.let { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { a.selectFirst(it)?.text()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .joinToString(" ").ifBlank { a.selectFirst("nickname")?.text().orEmpty() }
        }.orEmpty()
        binaries = doc.select("binary").associateBy { it.attr("id") }
        val body = doc.select("FictionBook > body, body").firstOrNull { it.attr("name").isBlank() || it.attr("name") == "main" }
            ?: throw EpubException("В FB2 нет текста (body)")
        val list = ArrayList<Element>()
        fun collect(parent: Element) {
            for (s in parent.children().filter { it.tagName() == "section" }) {
                val subs = s.children().filter { it.tagName() == "section" }
                val ownText = s.children().any { it.tagName() in setOf("p", "poem", "cite", "subtitle", "image", "empty-line", "table") }
                if (subs.isNotEmpty() && !ownText) collect(s) else list += s
            }
        }
        collect(body)
        sections = list.ifEmpty { listOf(body) }
        chapters = sections.mapIndexed { i, s ->
            EpubChapter(i, "fb2:$i", s.children().firstOrNull { it.tagName() == "title" }?.text()?.trim()?.ifBlank { null } ?: "Раздел ${i + 1}")
        }
        toc = chapters.map { TocEntry(it.title, it.index, 0) }
    }

    override fun blocks(chapterIndex: Int): List<EpubBlock> {
        val out = ArrayList<EpubBlock>()
        fun walk(el: Element, heading: Boolean) {
            for (c in el.children()) {
                when (c.tagName()) {
                    "title", "subtitle" -> walk(c, heading = true)
                    "p", "v", "text-author" -> {
                        val t = text(c)
                        if (t.isNotEmpty()) out += EpubBlock.Text(t, heading)
                    }
                    "image" -> imageId(c)?.let { out += EpubBlock.Image(it) }
                    "empty-line" -> Unit
                    else -> walk(c, heading) // section, poem, stanza, cite, epigraph, annotation…
                }
            }
        }
        walk(sections[chapterIndex], heading = false)
        return out
    }

    private fun text(el: Element): String = buildString {
        for (n in el.childNodes()) {
            when (n) {
                is TextNode -> append(n.text())
                is Element -> if (n.tagName() != "a" || n.attr("type") != "note") append(text(n))
            }
        }
    }.replace(Regex("[ \\t\\u00A0]+"), " ").trim(' ', '　')

    private fun imageId(el: Element): String? =
        el.attributes().firstOrNull { it.key.endsWith("href") }?.value?.removePrefix("#")?.takeIf { it.isNotBlank() }

    override fun bytes(path: String): ByteArray? = binaries[path]?.let { runCatching { Base64.getMimeDecoder().decode(it.text()) }.getOrNull() }

    override fun cover(): ByteArray? =
        doc.selectFirst("description > title-info > coverpage > image")?.let { imageId(it) }?.let { bytes(it) }

    override fun close() = Unit

    private companion object {
        /** The text is already decoded; drop the XML declaration so the parser does not re-decode it. */
        fun stripDeclaredEncoding(s: String) = s.replaceFirst(Regex("^\\s*<\\?xml[^>]*\\?>"), "")
    }
}
