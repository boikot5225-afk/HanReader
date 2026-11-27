package com.hanreader.app.web

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.URI
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One paragraph of an article. [heading]: 0 for text, 2..6 for a heading; [quote]: inside a blockquote. */
data class WebBlock(val text: String, val heading: Int = 0, val quote: Boolean = false)

data class WebArticle(val url: String, val title: String, val site: String, val author: String,
    val language: String, val blocks: List<WebBlock>)

/** A page could not become an article; [message] is shown to the reader as is. */
class WebArticleException(message: String) : Exception(message)

/**
 * A web page → the text of its article (a small «reader mode» on Jsoup, no WebView and no
 * scripts) → an EPUB that the library opens like any other book.
 */
object WebArticles {
    const val MAX_CHARS = 600_000
    private const val MAX_BLOCKS = 4_000
    const val NO_TEXT = "На странице не нашлось текста статьи. Возможно, сайт показывает его только в браузере: " +
        "выдели текст там и нажми «Поделиться» → Amauta Reader."

    private val URL = Regex("""https?://[^\s<>"'«»“”‘’]+""", RegexOption.IGNORE_CASE)

    /**
     * The link in shared text when the text is just a link: a bare address or what browsers send
     * («Title\nhttps://…», a short quote and a link). A long text that mentions a link stays text.
     */
    fun linkIn(text: String): String? {
        val t = text.trim()
        val found = URL.findAll(t).toList()
        if (found.size != 1) return null
        if (t.removeRange(found[0].range).trim().length > 300) return null
        return normalize(found[0].value)
    }

    /** https only (Android blocks plain http by default); no fragment (Chrome adds «#:~:text=…»). */
    fun normalize(raw: String): String? = runCatching {
        val uri = URI(raw.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '…'))
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = (uri.host ?: uri.rawAuthority?.substringAfterLast('@')?.substringBefore(':')?.let { IDN.toASCII(it) })
            ?.lowercase()?.takeIf { '.' in it } ?: return null
        val port = if (uri.port == -1 || uri.port == 80 || uri.port == 443) "" else ":${uri.port}"
        URI("https://$host$port${uri.rawPath.orEmpty().ifEmpty { "/" }}${uri.rawQuery?.let { "?$it" }.orEmpty()}").toASCIIString()
    }.getOrNull()

    fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.").ifBlank { "Веб-страница" }

    /** The article in [doc]; throws [WebArticleException] when there is no readable text. */
    fun extract(doc: Document, url: String): WebArticle {
        val site = metaContent(doc, "og:site_name").ifBlank { hostOf(url) }
        val title = title(doc, site)
        val author = metaContent(doc, "author", "article:author").takeIf { it.length in 2..120 && "://" !in it }.orEmpty()
        val language = doc.selectFirst("html")?.attr("lang")?.substringBefore('-')?.lowercase()?.takeIf { it.length in 2..3 }.orEmpty()
        val structured = structuredBody(doc)
        val body = doc.body() ?: throw WebArticleException(NO_TEXT)
        clean(body)
        var blocks = blocksOf(content(body))
        val fromStructured = structured?.let(::blocksFromPlain).orEmpty()
        if (fromStructured.sumOf { it.text.length } > blocks.sumOf { it.text.length } * 2) blocks = fromStructured
        blocks = tidy(blocks, title)
        if (!readable(blocks)) throw WebArticleException(NO_TEXT)
        return WebArticle(url, title, site, author, language, blocks)
    }

    /** Plain text (a page served as text/plain): paragraphs at blank lines or at each line. */
    fun fromPlainText(text: String, url: String): WebArticle {
        val blocks = tidy(blocksFromPlain(text), "")
        if (!readable(blocks)) throw WebArticleException(NO_TEXT)
        val title = blocks.first().text.let { if (it.length > 80) it.take(79) + "…" else it }
        return WebArticle(url, title, hostOf(url), "", "", blocks)
    }

    // ---- Metadata ----

    private fun metaContent(doc: Document, vararg names: String): String {
        for (n in names) {
            val v = doc.select("meta[property=$n], meta[name=$n]").firstOrNull()?.attr("content")?.trim()
            if (!v.isNullOrBlank()) return v.replace(Regex("\\s+"), " ")
        }
        return ""
    }

    private fun title(doc: Document, site: String): String {
        val og = metaContent(doc, "og:title", "twitter:title")
        val h1s = doc.select("h1").map { it.text().trim() }.filter { it.isNotBlank() }
        val h1 = h1s.firstOrNull().orEmpty()
        val page = doc.title().trim().let { t ->
            // «Headline | Site» / «Headline - Site» / «Headline_Site»: drop the site part.
            val parts = t.split(Regex("\\s+[|–—-]\\s+|_"))
            if (parts.size > 1 && parts.first().trim().length >= 4) parts.first().trim() else t
        }
        // A logo can be an <h1> too: prefer the one the page title repeats.
        val repeated = h1s.firstOrNull { it.length >= 3 && (it in og || it in doc.title()) }.orEmpty()
        return listOf(repeated, og, h1, page)
            .firstOrNull { it.isNotBlank() && it != site }?.take(300) ?: site
    }

    /** schema.org articleBody (JSON-LD): some sites send the text there and draw the page by script. */
    private fun structuredBody(doc: Document): String? {
        fun find(v: Any?): String? = when (v) {
            is JSONObject -> v.optString("articleBody").takeIf { it.isNotBlank() }
                ?: v.keys().asSequence().toList().firstNotNullOfOrNull { find(v.opt(it)) }
            is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { find(v.opt(it)) }
            else -> null
        }
        return doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { s ->
            runCatching { find(org.json.JSONTokener(s.data()).nextValue()) }.getOrNull()
        }
    }

    // ---- Finding the article ----

    private val UNLIKELY = Regex("-ad-|^ad-|-ads\\b|banner|breadcrumb|combx|comment|community|cookie|consent|disqus|footer|gdpr|" +
        "header|legend|login|menu|modal|newsletter|pager|pagination|paywall|popup|promo|recommend|related|remark|replies|" +
        "rss|share|sharing|shoutbox|sidebar|signup|skyscraper|social|sponsor|subscribe|supplemental|toolbar|widget",
        RegexOption.IGNORE_CASE)
    private val LIKELY = Regex("article|body|column|content|entry|main|post|story|text|prose", RegexOption.IGNORE_CASE)
    private val POSITIVE = Regex("article|body|content|entry|main|page|post|text|blog|story|prose", RegexOption.IGNORE_CASE)
    private val NEGATIVE = Regex("-ad-|banner|byline|comment|footer|footnote|masthead|media|meta|outbrain|promo|related|" +
        "scroll|share|shoutbox|sidebar|skyscraper|sponsor|shopping|tags|widget|nav|menu", RegexOption.IGNORE_CASE)

    private val BLOCKS = setOf("p", "div", "section", "article", "main", "blockquote", "pre", "li", "ul", "ol", "dl", "dd", "dt",
        "table", "tbody", "thead", "tr", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "center", "address")
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private fun clean(body: Element) {
        body.select("script,style,noscript,template,iframe,object,embed,form,input,button,select,textarea,label,svg,canvas," +
            "video,audio,picture,img,figure,figcaption,nav,aside,footer,dialog,menu,link,meta").remove()
        body.select("[hidden],[aria-hidden=true],[role=navigation],[role=banner],[role=contentinfo],[role=complementary]," +
            "[role=dialog],[role=alertdialog],[role=search],[role=menu]").remove()
        body.select("[style]").filter { Regex("display\\s*:\\s*none", RegexOption.IGNORE_CASE).containsMatchIn(it.attr("style")) }.forEach { it.remove() }
        body.select("header").filter { h -> h.parents().none { it.normalName() == "article" } }.forEach { it.remove() }
        val whole = body.text().length
        body.getAllElements().filter { e ->
            e !== body && e.normalName() !in setOf("article", "main") &&
                "${e.className()} ${e.id()}".let { UNLIKELY.containsMatchIn(it) && !LIKELY.containsMatchIn(it) } &&
                // A page wrapper («page has-sidebar») holds the article itself.
                e.text().length <= whole / 2
        }.forEach { it.remove() }
    }

    private fun weight(e: Element): Int {
        var w = 0
        val name = "${e.className()} ${e.id()}"
        if (NEGATIVE.containsMatchIn(name)) w -= 25
        if (POSITIVE.containsMatchIn(name)) w += 25
        if (e.attr("itemprop") == "articleBody") w += 50
        return w
    }

    private fun linkDensity(e: Element): Double {
        val all = e.text().length
        if (all == 0) return 0.0
        return e.select("a").sumOf { it.text().length }.toDouble() / all
    }

    private fun punctuation(text: String) = text.count { it == ',' || it == '，' || it == '、' || it == '。' || it == ';' || it == '；' }

    private fun hasBlockChild(e: Element) = e.children().any { it.normalName() in BLOCKS }

    /** The element(s) holding the article: paragraphs vote for their ancestors (as Readability does). */
    private fun content(body: Element): List<Element> {
        body.selectFirst("[itemprop=articleBody]")?.let { if (it.text().length > 200) return listOf(it) }
        val scores = LinkedHashMap<Element, Double>()
        fun init(e: Element): Double = scores.getOrPut(e) {
            weight(e) + when (e.normalName()) {
                "article" -> 10.0; "div", "main", "section" -> 5.0; "pre", "td", "blockquote" -> 3.0
                "ol", "ul", "dl", "dd", "dt", "li", "address" -> -3.0; "th" -> -5.0
                else -> 0.0
            }
        }
        val holders = body.getAllElements().filter { e ->
            when (e.normalName()) {
                "p", "pre" -> true
                "div", "section", "td", "article" -> !hasBlockChild(e)
                else -> false
            }
        }
        for (p in holders) {
            val text = p.text()
            if (text.length < 25) continue
            val score = 1.0 + punctuation(text) + minOf(text.length / 100, 3)
            var level = 0
            for (ancestor in p.parents()) {
                if (level == 3 || ancestor.normalName() == "html") break
                val share = when (level) { 0 -> 1.0; 1 -> 0.5; else -> 1.0 / (level * 3) }
                scores[ancestor] = init(ancestor) + score * share
                level++
            }
        }
        val (best, bestScore) = scores.entries.map { it.key to it.value * (1 - linkDensity(it.key)) }.maxByOrNull { it.second }
            ?: return listOf(body)
        val parent = best.parent() ?: return listOf(best)
        val threshold = maxOf(10.0, bestScore * 0.2)
        return parent.children().filter { sibling ->
            if (sibling === best) return@filter true
            val s = scores[sibling]?.let { it * (1 - linkDensity(sibling)) } ?: 0.0
            if (s >= threshold) return@filter true
            if (sibling.normalName() != "p") return@filter false
            val text = sibling.text(); val density = linkDensity(sibling)
            (text.length > 80 && density < 0.25) || (text.length in 1..80 && density == 0.0 && punctuation(text) > 0)
        }
    }

    // ---- Paragraphs ----

    private class Sink {
        val blocks = ArrayList<WebBlock>()
        private val text = StringBuilder()
        private var linked = 0
        fun add(s: String, inLink: Boolean) {
            if (s.isEmpty()) return
            if (text.isEmpty() || text.last() == ' ') text.append(s.trimStart()) else text.append(s)
            if (inLink) linked += s.trim().length
        }
        fun flush(heading: Int, quote: Boolean) {
            val t = text.toString().replace(Regex("\\s+"), " ").trim()
            // Paragraphs that are mostly links: «Read more», tag lists, menus left inside the article.
            if (t.isNotEmpty() && !(linked > t.length * 0.6 && t.length < 400)) {
                blocks += WebBlock(t, heading, quote && heading == 0)
            }
            text.setLength(0); linked = 0
        }
    }

    private fun blocksOf(roots: List<Element>): List<WebBlock> {
        val sink = Sink()
        fun visit(node: Node, heading: Int, quote: Boolean, link: Boolean) {
            when (node) {
                is TextNode -> sink.add(node.text(), link)
                is Element -> {
                    val tag = node.normalName()
                    when {
                        tag == "br" -> sink.flush(heading, quote)
                        tag == "tr" -> {
                            sink.flush(heading, quote)
                            sink.add(node.select("th,td").joinToString(" | ") { it.text() }, false)
                            sink.flush(0, quote)
                        }
                        tag in BLOCKS -> {
                            val h = if (tag in HEADINGS) tag.removePrefix("h").toInt().coerceIn(2, 6) else heading
                            val q = quote || tag == "blockquote"
                            sink.flush(heading, quote)
                            node.childNodes().forEach { visit(it, h, q, link) }
                            sink.flush(h, q)
                        }
                        else -> node.childNodes().forEach { visit(it, heading, quote, link || tag == "a") }
                    }
                }
            }
        }
        roots.forEach { visit(it, 0, false, false); sink.flush(0, false) }
        return sink.blocks
    }

    private fun blocksFromPlain(text: String): List<WebBlock> {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n')
        val parts = if ("\n\n" in clean) clean.split(Regex("\n\\s*\n")) else clean.split('\n')
        return parts.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.map { WebBlock(it) }
    }

    private fun tidy(blocks: List<WebBlock>, title: String): List<WebBlock> {
        val out = ArrayList<WebBlock>()
        var total = 0
        for (b in blocks) {
            if (out.isEmpty() && b.text == title) continue
            if (out.lastOrNull()?.text == b.text) continue
            out += b; total += b.text.length
            if (out.size >= MAX_BLOCKS || total >= MAX_CHARS) break
        }
        while (out.isNotEmpty() && out.last().heading > 0) out.removeAt(out.lastIndex)
        return out
    }

    private fun readable(blocks: List<WebBlock>): Boolean {
        val text = blocks.filter { it.heading == 0 }
        val letters = text.sumOf { b -> b.text.count { it.isLetter() } }
        val han = text.sumOf { b -> b.text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }
        return letters >= 150 || han >= 50
    }

    // ---- EPUB ----

    private fun esc(s: String) = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> if (c.code < 0x20 && c != '\n' && c != '\t') append(' ') else append(c)
        }
    }

    /** A one-chapter EPUB 3; the address and site stay in its metadata (dc:source, dc:publisher). */
    fun epub(article: WebArticle, savedDate: String): ByteArray {
        val lang = article.language.ifBlank { "und" }
        val creator = article.author.ifBlank { article.site }
        val body = buildString {
            append("<h1>").append(esc(article.title)).append("</h1>\n")
            var inQuote = false
            for (b in article.blocks) {
                if (b.quote != inQuote) { append(if (b.quote) "<blockquote>\n" else "</blockquote>\n"); inQuote = b.quote }
                if (b.heading > 0) append("<h${b.heading}>").append(esc(b.text)).append("</h${b.heading}>\n")
                else append("<p>").append(esc(b.text)).append("</p>\n")
            }
            if (inQuote) append("</blockquote>\n")
        }
        val text = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="${esc(lang)}" lang="${esc(lang)}">
<head><meta charset="utf-8"/><title>${esc(article.title)}</title></head>
<body>
$body</body>
</html>
"""
        val nav = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><meta charset="utf-8"/><title>${esc(article.title)}</title></head>
<body><nav epub:type="toc"><ol><li><a href="text.xhtml">${esc(article.title)}</a></li></ol></nav></body>
</html>
"""
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">${esc(article.url)}</dc:identifier>
<dc:title>${esc(article.title)}</dc:title>
<dc:creator>${esc(creator)}</dc:creator>
<dc:publisher>${esc(article.site)}</dc:publisher>
<dc:source>${esc(article.url)}</dc:source>
<dc:date>${esc(savedDate)}</dc:date>
<dc:language>${esc(lang)}</dc:language>
<meta property="dcterms:modified">${esc(savedDate)}T00:00:00Z</meta>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="text" href="text.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine><itemref idref="text"/></spine>
</package>
"""
        val container = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime); zip.closeEntry()
            for ((name, content) in listOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf,
                "OEBPS/nav.xhtml" to nav, "OEBPS/text.xhtml" to text)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
