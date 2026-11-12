package com.hanreader.app.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** A spine item: one XHTML file of the book, in reading order. */
data class EpubChapter(val index: Int, val path: String, val title: String)

/** A table-of-contents line pointing into a spine item. */
data class TocEntry(val title: String, val chapterIndex: Int, val depth: Int)

/** What the reader renders: paragraphs, headings and images, with basic paragraph geometry. */
sealed interface EpubBlock {
    /** [notes]: footnote marks inside [text] (the «4» of «Villa4»), resolved by [BookSource.note]. */
    data class Text(val text: String, val heading: Boolean, val notes: List<EpubNote> = emptyList(), val style: EpubTextStyle = EpubTextStyle()) : EpubBlock
    data class Image(val path: String) : EpubBlock
}

/**
 * A footnote mark: characters [start, end) of a paragraph, and where its text is: "a:path#id" for
 * a link, "n:4" for a bare superscript number (the notes are then in a «Notas» / «Notes» section).
 */
data class EpubNote(val start: Int, val end: Int, val ref: String)

class EpubException(message: String) : Exception(message)

/**
 * Minimal EPUB 2/3 reader: container.xml → OPF (metadata, manifest, spine) → NCX or nav TOC.
 * Chapters are parsed on demand; nothing is kept in memory except the spine and TOC.
 */
class EpubBook(file: File) : BookSource {
    private val zip = try {
        ZipFile(file)
    } catch (e: Exception) {
        throw EpubException("Файл не является EPUB (не ZIP-архив)")
    }

    override val title: String
    override val author: String
    val language: String
    override val chapters: List<EpubChapter>
    override val toc: List<TocEntry>
    /** Archive path of the cover image, from the OPF (EPUB 3 cover-image or EPUB 2 <meta name="cover">). */
    private val coverPath: String?

    init {
        val container = xml(text("META-INF/container.xml") ?: throw EpubException("В EPUB нет META-INF/container.xml"))
        val opfPath = container.selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotBlank() }
            ?: throw EpubException("В container.xml не указан OPF")
        val opf = xml(text(opfPath) ?: throw EpubException("Не найден $opfPath"))
        val base = opfPath.substringBeforeLast('/', "")

        title = opf.firstText("dc|title", "title").ifBlank { file.nameWithoutExtension }
        author = opf.firstText("dc|creator", "creator")
        language = opf.firstText("dc|language", "language")

        data class Item(val path: String, val type: String, val properties: String)
        val manifest = HashMap<String, Item>()
        opf.select("manifest > item").forEach {
            manifest[it.attr("id")] = Item(resolve(base, it.attr("href")), it.attr("media-type"), it.attr("properties"))
        }
        val coverId = opf.select("metadata > meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        coverPath = manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }?.path
            ?: coverId?.let { manifest[it] }?.takeIf { it.type.startsWith("image/") }?.path
        val spine = opf.selectFirst("spine")
        val spinePaths = spine?.select("itemref")?.mapNotNull { manifest[it.attr("idref")]?.path }.orEmpty()
        if (spinePaths.isEmpty()) throw EpubException("В книге нет глав (пустой spine)")
        val spineIndex = spinePaths.withIndex().associate { it.value to it.index }

        val tocEntries = ArrayList<TocEntry>()
        val nav = manifest.values.firstOrNull { "nav" in it.properties.split(' ') }
        val ncx = manifest[spine?.attr("toc").orEmpty()]
            ?: manifest.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
        if (nav != null) parseNav(nav.path, spineIndex, tocEntries)
        if (tocEntries.isEmpty() && ncx != null) parseNcx(ncx.path, spineIndex, tocEntries)
        toc = tocEntries

        val firstTitle = HashMap<Int, String>()
        tocEntries.forEach { firstTitle.putIfAbsent(it.chapterIndex, it.title) }
        chapters = spinePaths.mapIndexed { i, path ->
            EpubChapter(i, path, firstTitle[i] ?: "Раздел ${i + 1}")
        }
    }

    /** Paragraphs, headings and images of one spine item, in order. */
    override fun blocks(chapterIndex: Int): List<EpubBlock> {
        val chapter = chapters[chapterIndex]
        val html = text(chapter.path) ?: return emptyList()
        val doc = Jsoup.parse(html)
        val out = ArrayList<EpubBlock>()
        val base = chapter.path.substringBeforeLast('/', "")
        val sheets = doc.select("link[href]").filter { it.attr("rel").equals("stylesheet", true) }
            .mapNotNull { text(resolve(base, it.attr("href"))) } + doc.select("style").map { it.data() }
        BlockWalker(base, out, EpubStyles(doc, sheets)).walk(doc.body(), heading = false)
        return out
    }

    private val noteDocs = HashMap<String, Document>()

    /**
     * A footnote's text. A link: the element it points to (or the paragraph holding it). A bare
     * number: the line «4. …» of the book's notes section («Notas», «Notes», «Примечания»), in the
     * part for this chapter («Capítulo 4: Cárteles» for the chapter «4. Cárteles»).
     */
    override fun note(chapterIndex: Int, ref: String): String? = runCatching {
        when {
            ref.startsWith("a:") -> {
                val target = ref.removePrefix("a:")
                val path = target.substringBefore('#').let { if (it == CURRENT) chapters[chapterIndex].path else it }
                val id = target.substringAfter('#')
                val doc = synchronized(noteDocs) { noteDocs.getOrPut(path) { Jsoup.parse(text(path) ?: return@runCatching null) } }
                val el = doc.getElementById(id) ?: doc.selectFirst("a[name=$id]") ?: return@runCatching null
                var block: Element = el
                while (block.text().trim().length < 4 && block.parent() != null && block.parent() != doc.body()) block = block.parent()!!
                if (block.normalName() in setOf("a", "sup", "span", "em", "i", "b")) {
                    block = block.parents().firstOrNull { it.normalName() in BLOCKS } ?: block
                }
                block.text().trim().take(4000).ifEmpty { null }
            }
            ref.startsWith("n:") -> numberedNote(chapterIndex, ref.removePrefix("n:"))
            else -> null
        }
    }.getOrNull()

    private class NoteGroup(val title: String, val notes: Map<String, String>)

    /** The book's notes section, by part: the part's heading and its numbered notes. Built once. */
    private val noteGroups: List<NoteGroup> by lazy {
        val groups = ArrayList<NoteGroup>()
        for (c in chapters) {
            val texts = blocks(c.index).filterIsInstance<EpubBlock.Text>()
            val first = texts.firstOrNull()?.text?.trim()?.lowercase().orEmpty()
            val isNotes = first in NOTE_TITLES || NOTE_FILE.containsMatchIn(c.path.substringAfterLast('/'))
            if (!isNotes) continue
            var title = ""
            var notes = LinkedHashMap<String, String>()
            fun close() { if (notes.isNotEmpty()) groups += NoteGroup(title, notes) }
            for (t in texts) {
                for (line in t.text.lines()) {
                    val l = line.trim()
                    val m = NOTE_LINE.matchEntire(l)
                    when {
                        m != null -> notes.putIfAbsent(m.groupValues[1], m.groupValues[2].trim())
                        PART_LINE.containsMatchIn(l) && l.length < 120 -> { close(); title = l; notes = LinkedHashMap() }
                    }
                }
            }
            close()
        }
        groups
    }

    private fun numberedNote(chapterIndex: Int, number: String): String? {
        val groups = noteGroups
        if (groups.isEmpty()) return null
        if (groups.size == 1) return groups[0].notes[number]
        val chapterTitle = chapters[chapterIndex].title
        fun core(t: String) = t.substringAfter(':').lowercase().replace(Regex("^[\\s\\d.)\\-–—]+"), "")
            .replace(Regex("^(cap[ií]tulo|chapter|глава)\\s*\\S*\\s*"), "").trim()
        fun num(t: String) = Regex("\\d{1,3}").find(t)?.value
        val c = core(chapterTitle)
        val byTitle = groups.firstOrNull { g -> val gc = core(g.title); c.isNotEmpty() && gc.isNotEmpty() && (gc == c || gc.contains(c) || c.contains(gc)) }
        val byNumber = num(chapterTitle)?.let { n -> groups.firstOrNull { num(it.title) == n } }
        return (byTitle ?: byNumber)?.notes?.get(number)
    }

    override fun cover(): ByteArray? {
        coverPath?.let { p -> bytes(p)?.let { return it } }
        // Fallback: the first image of the first chapters.
        for (i in 0 until minOf(3, chapters.size)) {
            blocks(i).firstOrNull { it is EpubBlock.Image }?.let { return bytes((it as EpubBlock.Image).path) }
        }
        return null
    }

    /** A file of the book; null if absent or larger than [BookLimits.MAX_ENTRY] (a broken or malicious archive). */
    override fun bytes(path: String): ByteArray? = zip.getEntry(path)?.let { e ->
        runCatching { zip.getInputStream(e).use { BookLimits.read(it, BookLimits.MAX_ENTRY) } }.getOrNull()
    }

    override fun close() = zip.close()

    private fun text(path: String): String? = bytes(path)?.toString(Charsets.UTF_8)

    private fun parseNcx(path: String, spine: Map<String, Int>, out: MutableList<TocEntry>) {
        val doc = xml(text(path) ?: return)
        val base = path.substringBeforeLast('/', "")
        fun visit(point: Element, depth: Int) {
            val label = point.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
            val src = point.selectFirst("content")?.attr("src").orEmpty()
            val index = spine[resolve(base, src.substringBefore('#'))]
            if (label.isNotEmpty() && index != null) out += TocEntry(label, index, depth)
            point.children().filter { it.tagName() == "navPoint" }.forEach { visit(it, depth + 1) }
        }
        doc.selectFirst("navMap")?.children()?.filter { it.tagName() == "navPoint" }?.forEach { visit(it, 0) }
    }

    private fun parseNav(path: String, spine: Map<String, Int>, out: MutableList<TocEntry>) {
        val doc = Jsoup.parse(text(path) ?: return)
        val base = path.substringBeforeLast('/', "")
        val nav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav") ?: return
        fun visit(list: Element, depth: Int) {
            list.children().filter { it.tagName() == "li" }.forEach { li ->
                val a = li.children().firstOrNull { it.tagName() == "a" || it.tagName() == "span" }
                val label = a?.text()?.trim().orEmpty()
                val index = spine[resolve(base, a?.attr("href").orEmpty().substringBefore('#'))]
                if (label.isNotEmpty() && index != null) out += TocEntry(label, index, depth)
                li.children().filter { it.tagName() == "ol" }.forEach { visit(it, depth + 1) }
            }
        }
        nav.children().filter { it.tagName() == "ol" }.forEach { visit(it, 0) }
    }

    private class BlockWalker(private val base: String, private val out: MutableList<EpubBlock>, private val styles: EpubStyles) {
        private var bufferStyle = EpubTextStyle()
        private val buffer = StringBuilder()
        private var bufferHeading = false

        fun walk(el: Element, heading: Boolean) {
            for (node in el.childNodes()) visit(node, heading)
            flush()
        }

        private fun visit(node: Node, heading: Boolean) {
            when (node) {
                is TextNode -> append(node.text(), heading)
                is Element -> {
                    val tag = node.normalName()
                    when {
                        tag in SKIP -> Unit
                        tag == "br" -> buffer.append('\n')
                        noteRef(node) != null -> {
                            if (buffer.isEmpty()) bufferHeading = heading
                            buffer.append(MARK_START).append(noteRef(node)).append(MARK_TEXT).append(node.text().trim()).append(MARK_END)
                        }
                        tag == "img" || tag == "image" -> {
                            val src = node.attr("src").ifEmpty { node.attr("xlink:href") }.ifEmpty { node.attr("href") }
                            if (src.isNotBlank()) {
                                flush()
                                out += EpubBlock.Image(resolve(base, src))
                            }
                        }
                        tag in BLOCKS -> {
                            flush()
                            val isHeading = heading || tag in HEADINGS
                            val parentStyle = bufferStyle
                            bufferStyle = styles.textStyle(node)
                            for (child in node.childNodes()) visit(child, isHeading)
                            flush()
                            bufferStyle = parentStyle
                        }
                        else -> for (child in node.childNodes()) visit(child, heading)
                    }
                }
            }
        }

        private fun append(text: String, heading: Boolean) {
            if (text.isBlank() && buffer.isEmpty()) return
            if (buffer.isEmpty()) bufferHeading = heading
            buffer.append(text)
        }

        /** A footnote mark: a link to a note, or a bare superscript number; null for other elements. */
        private fun noteRef(el: Element): String? {
            val tag = el.normalName()
            val text = el.text().trim()
            if (tag == "a") {
                val href = el.attr("href").ifEmpty { el.attr("xlink:href") }
                if (!href.contains('#')) return null
                val noteref = el.attr("epub:type").contains("noteref") || el.attr("role").contains("noteref")
                val short = text.length in 1..4 && (el.selectFirst("sup") != null || NOTE_TEXT.matches(text))
                if (!noteref && !short) return null
                val path = href.substringBefore('#').let { if (it.isEmpty()) "" else resolve(base, it) }
                return "a:" + path.ifEmpty { CURRENT } + "#" + href.substringAfter('#')
            }
            if (tag == "sup" && el.selectFirst("a") == null) {
                val n = NOTE_NUMBER.matchEntire(text)?.groupValues?.get(1) ?: return null
                return "n:$n"
            }
            return null
        }

        fun flush() {
            val raw = buffer.toString()
                .lines()
                .joinToString("\n") { it.replace(SPACES, " ").trim(' ', '　', ' ') }
                .trim()
            buffer.setLength(0)
            // Footnote marks travel through the text as MARK_START ref MARK_TEXT shown MARK_END.
            val notes = ArrayList<EpubNote>()
            val text = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == MARK_START) {
                    val t = raw.indexOf(MARK_TEXT, i)
                    val e = raw.indexOf(MARK_END, i)
                    if (t > i && e > t) {
                        val shown = raw.substring(t + 1, e)
                        val start = text.length
                        text.append(shown)
                        if (shown.isNotEmpty()) notes += EpubNote(start, text.length, raw.substring(i + 1, t))
                        i = e + 1
                        continue
                    }
                }
                text.append(c)
                i++
            }
            val clean = text.toString()
            if (clean.isNotBlank()) out += EpubBlock.Text(clean, bufferHeading, notes, bufferStyle)
        }
    }

    companion object {
        private val SKIP = setOf("script", "style", "head", "rt", "rp", "title")
        private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        private val BLOCKS = HEADINGS + setOf(
            "p", "div", "section", "article", "blockquote", "li", "ul", "ol", "dd", "dt", "dl",
            "table", "tr", "td", "th", "pre", "figure", "figcaption", "aside", "header", "footer",
            "nav", "hr", "body", "main", "center"
        )
        private val SPACES = Regex("[ \\t\\r\\u00A0]+")
        /** The first line of a notes section. */
        private val NOTE_TITLES = setOf("notas", "notes", "примечания", "сноски", "комментарии", "notas del autor", "endnotes", "注释", "注", "尾注")
        private val NOTE_FILE = Regex("(?i)nota|note|footnote|endnote|primech")
        /** «4. text», «4) text». */
        private val NOTE_LINE = Regex("(\\d{1,3})[.)]\\s+(.+)")
        /** The heading of a part of the notes: «Capítulo 4: Cárteles», «Chapter 2», «Глава 3», «第三章». */
        private val PART_LINE = Regex("(?i)^(cap[ií]tulo|chapter|глава|parte|part)\\b|^第.{1,6}[章回]")
        private const val MARK_START = '\uE000'
        private const val MARK_TEXT = '\uE001'
        private const val MARK_END = '\uE002'
        /** A link inside the same file. */
        const val CURRENT = "."
        private val NOTE_TEXT = Regex("[\\[(]?(\\d{1,3}|[*†‡a-z])[\\])]?")
        private val NOTE_NUMBER = Regex("[\\[(]?(\\d{1,3})[\\])]?")

        private fun xml(text: String): Document = Jsoup.parse(text, "", Parser.xmlParser())

        private fun Document.firstText(vararg selectors: String): String {
            for (s in selectors) {
                val v = try { selectFirst(s)?.text()?.trim() } catch (_: Exception) { null }
                if (!v.isNullOrEmpty()) return v
            }
            return ""
        }

        /** Resolves an href relative to a directory inside the archive, handling "../" and %-escapes. */
        fun resolve(base: String, href: String): String {
            val decoded = try { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { href }
            val parts = ArrayList<String>()
            if (!decoded.startsWith("/") && base.isNotEmpty()) parts += base.split('/')
            for (p in decoded.trimStart('/').split('/')) {
                when (p) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts += p
                }
            }
            return parts.joinToString("/")
        }
    }
}
