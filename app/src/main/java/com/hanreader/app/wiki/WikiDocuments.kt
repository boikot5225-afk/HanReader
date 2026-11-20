package com.hanreader.app.wiki

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

data class WikiBlock(val text: String, val heading: Int = 0)
data class WikiDocument(val article: WikiArticle, val blocks: List<WikiBlock>) {
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
        body.select("img,video,audio,source,svg").remove()
        val blocks = ArrayList<WikiBlock>()
        var total = 0
        body.select("p,h2,h3,h4,h5,h6,li,blockquote,pre,tr").forEach { element ->
            val tag = element.normalName()
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
        return WikiDocument(expected.copy(fetchedAt = json.getLong("amautaFetchedAt")), blocks).also(::validate)
    }

    fun validate(document: WikiDocument) {
        validateArticle(document.article)
        require(document.blocks.isNotEmpty() && document.blocks.size <= MAX_BLOCKS)
        require(document.blocks.sumOf { it.text.length } <= MAX_CHARS)
        require(document.blocks.any { it.heading == 0 && it.text.isNotBlank() })
        document.blocks.forEach { require(it.text.isNotBlank() && it.text.length <= 3_000 && (it.heading == 0 || it.heading in 2..6)) }
    }

    fun encode(document: WikiDocument): String {
        validate(document)
        val a = document.article
        return JSONObject().put("version", 1).put("language", a.language).put("id", a.id).put("revision", a.revision)
            .put("title", a.title).put("extract", a.extract).put("fetchedAt", a.fetchedAt)
            .put("blocks", JSONArray().apply { document.blocks.forEach { put(JSONObject().put("text", it.text).put("heading", it.heading)) } })
            .toString().also { require(it.toByteArray().size <= MAX_FILE_BYTES) }
    }

    fun decode(text: String): WikiDocument {
        require(text.toByteArray().size <= MAX_FILE_BYTES)
        val json = JSONObject(text); require(json.getInt("version") == 1)
        val article = WikiArticle(json.getString("language"), json.getLong("id"), json.getString("title"), json.getString("extract"),
            json.getLong("revision"), json.getLong("fetchedAt"), false)
        val rows = json.getJSONArray("blocks"); require(rows.length() in 1..MAX_BLOCKS)
        return WikiDocument(article, List(rows.length()) { i -> rows.getJSONObject(i).let { WikiBlock(it.getString("text"), it.getInt("heading")) } }).also(::validate)
    }
}
