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

/** What the reader renders: paragraphs, headings and images. Styling from the book is dropped. */
sealed interface EpubBlock {
    data class Text(val text: String, val heading: Boolean) : EpubBlock
    data class Image(val path: String) : EpubBlock
}

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
        BlockWalker(chapter.path.substringBeforeLast('/', ""), out).walk(doc.body(), heading = false)
        return out
    }

    override fun cover(): ByteArray? {
        coverPath?.let { p -> bytes(p)?.let { return it } }
        // Fallback: the first image of the first chapters.
        for (i in 0 until minOf(3, chapters.size)) {
            blocks(i).firstOrNull { it is EpubBlock.Image }?.let { return bytes((it as EpubBlock.Image).path) }
        }
        return null
    }

    override fun bytes(path: String): ByteArray? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

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

    private class BlockWalker(private val base: String, private val out: MutableList<EpubBlock>) {
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
                            for (child in node.childNodes()) visit(child, isHeading)
                            flush()
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

        fun flush() {
            val text = buffer.toString()
                .lines()
                .joinToString("\n") { it.replace(SPACES, " ").trim(' ', '　', ' ') }
                .trim()
            buffer.setLength(0)
            if (text.isNotEmpty()) out += EpubBlock.Text(text, bufferHeading)
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
