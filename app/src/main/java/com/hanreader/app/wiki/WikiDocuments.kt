package com.hanreader.app.wiki

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

data class WikiBlock(val text: String, val heading: Int = 0)
/** Anchored to source paragraphs, never to screen coordinates or layout blocks. */
data class WikiImage(val url: String, val title: String, val caption: String, val width: Int, val height: Int, val beforeBlock: Int)
data class WikiDocument(val article: WikiArticle, val blocks: List<WikiBlock>, val images: List<WikiImage> = emptyList()) {
    val key get() = "v1:${article.language}:${article.id}:${article.revision}"
}

/** Convert a pinned MediaWiki revision into bounded, inert text for the existing Ruby renderer. */
object WikiDocuments {
    const val MAX_FILE_BYTES = 2_000_000L
    private const val MAX_CHARS = 400_000
    private const val MAX_BLOCKS = 1_200

    fun validateArticle(a: WikiArticle) {
        require(a.language in WikipediaRepository.languages && a.id > 0 && a.revision > 0 && !a.ambiguous)
        require(a.title.isNotBlank() && a.title.length <= 300 && a.extract.isNotBlank() && a.extract.length <= 12_000 && a.fetchedAt >= 0)
    }

    fun parse(expected: WikiArticle, json: JSONObject): WikiDocument {
        validateArticle(expected)
        val page = json.getJSONObject("parse")
        require(page.getLong("pageid") == expected.id && page.getLong("revid") == expected.revision)
        val html = page.getString("text")
        require(html.toByteArray().size <= MAX_FILE_BYTES)
        val body = Jsoup.parseBodyFragment(html).body()
        body.select("script,style,noscript,iframe,object,embed,form,input,button,.mw-editsection,.navbox,.sidebar,.toc,.noprint,.metadata,.hatnote,.sistersitebox,.portal,.mw-empty-elt,[hidden],[aria-hidden=true],sup.reference").remove()
        body.select("math").forEach { math -> math.replaceWith(TextNode(math.attr("alttext").ifBlank { math.text() })) }
        // Only our own inert placeholders enter the ordered traversal below.
        body.select("wiki-image").remove()
        val candidates = ArrayList<WikiImage>()
        body.select("img").forEach { img ->
            val url = WikiImages.normalize(img.attr("src")) ?: return@forEach
            val width = img.attr("width").toIntOrNull() ?: 0
            val height = img.attr("height").toIntOrNull() ?: 0
            val title = WikiImages.fileTitle(url)
            if (candidates.size < 40 && title != null && width in 80..20000 && height in 60..20000 &&
                img.parents().none { it.hasClass("mwe-math-element") }) {
                val caption = img.closest("figure,.thumb")?.selectFirst("figcaption,.thumbcaption")?.text()
                    ?: img.attr("alt")
                val index = candidates.size
                candidates += WikiImage(url, title, caption.take(1000), width, height, 0)
                img.after(org.jsoup.nodes.Element("wiki-image").attr("index", index.toString()))
            }
        }
        body.select("img,video,audio,source,svg").remove()
        val blocks = ArrayList<WikiBlock>()
        val images = ArrayList<WikiImage>()
        var total = 0
        body.select("p,h2,h3,h4,h5,h6,li,blockquote,pre,tr,wiki-image").forEach { element ->
            val tag = element.normalName()
            if (tag == "wiki-image") {
                images += candidates[element.attr("index").toInt()].copy(beforeBlock = blocks.size)
                return@forEach
            }
            val parents = element.parents().map { it.normalName() }
            if (parents.any { it in setOf("blockquote", "pre") }) return@forEach
            if (tag != "li" && "li" in parents) return@forEach
            if (tag != "tr" && "tr" in parents) return@forEach
            if (tag == "p" && parents.any { it in setOf("li", "blockquote", "pre") }) return@forEach
            val copy = element.clone()
            if (tag == "li") copy.select("ul,ol").remove()
            if (tag == "tr") copy.select("table").remove()
            val text = if (tag == "tr") copy.select("th,td").joinToString(" | ") { it.text() }
                else copy.text()
            if (text.isBlank()) return@forEach
            val heading = tag.removePrefix("h").toIntOrNull()?.takeIf { it in 2..6 } ?: 0
            total += text.length
            require(total <= MAX_CHARS)
            var rest = text.trim()
            while (rest.isNotEmpty()) {
                val cut = if (rest.length <= 3_000) rest.length else {
                    val space = rest.lastIndexOf(' ', 3_000)
                    if (space > 1_500) space else if (rest[2_999].isHighSurrogate()) 2_999 else 3_000
                }
                blocks += WikiBlock(rest.substring(0, cut), heading)
                require(blocks.size <= MAX_BLOCKS)
                rest = rest.substring(cut).trimStart()
            }
        }
        return WikiDocument(expected.copy(fetchedAt = json.getLong("amautaFetchedAt")), blocks, images).also(::validate)
    }

    fun validate(document: WikiDocument) {
        validateArticle(document.article)
        require(document.blocks.isNotEmpty() && document.blocks.size <= MAX_BLOCKS)
        require(document.blocks.sumOf { it.text.length } <= MAX_CHARS)
        require(document.blocks.any { it.heading == 0 && it.text.isNotBlank() })
        document.blocks.forEach { require(it.text.isNotBlank() && it.text.length <= 3_000 && (it.heading == 0 || it.heading in 2..6)) }
        require(document.images.size <= 40)
        document.images.forEach {
            require(WikiImages.fileTitle(it.url) == it.title && it.caption.length <= 1000)
            require(it.width in 80..20000 && it.height in 60..20000 && it.beforeBlock in 0..document.blocks.size)
        }
    }

    fun encode(document: WikiDocument): String {
        validate(document)
        val a = document.article
        return JSONObject().put("version", 2).put("language", a.language).put("id", a.id).put("revision", a.revision)
            .put("title", a.title).put("extract", a.extract).put("fetchedAt", a.fetchedAt)
            .put("blocks", JSONArray().apply { document.blocks.forEach { put(JSONObject().put("text", it.text).put("heading", it.heading)) } })
            .put("images", JSONArray().apply { document.images.forEach { put(JSONObject().put("url", it.url).put("title", it.title)
                .put("caption", it.caption).put("width", it.width).put("height", it.height).put("beforeBlock", it.beforeBlock)) } })
            .toString().also { require(it.toByteArray().size <= MAX_FILE_BYTES) }
    }

    fun decode(text: String): WikiDocument {
        require(text.toByteArray().size <= MAX_FILE_BYTES)
        val json = JSONObject(text); require(json.getInt("version") in 1..2)
        val article = WikiArticle(json.getString("language"), json.getLong("id"), json.getString("title"), json.getString("extract"),
            json.getLong("revision"), json.getLong("fetchedAt"), false)
        val rows = json.getJSONArray("blocks"); require(rows.length() in 1..MAX_BLOCKS)
        val images = json.optJSONArray("images") ?: JSONArray()
        require(images.length() <= 40)
        return WikiDocument(article, List(rows.length()) { i -> rows.getJSONObject(i).let { WikiBlock(it.getString("text"), it.getInt("heading")) } },
            List(images.length()) { i -> images.getJSONObject(i).let { WikiImage(it.getString("url"), it.getString("title"), it.getString("caption"),
                it.getInt("width"), it.getInt("height"), it.getInt("beforeBlock")) } }).also(::validate)
    }
}
