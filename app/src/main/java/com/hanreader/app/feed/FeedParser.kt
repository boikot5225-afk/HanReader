package com.hanreader.app.feed

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Pure parsers: no network, no Android. Anything malformed is dropped item by item. */
object FeedParser {
    private const val MAX_ITEMS = FeedItems.MAX_PER_SOURCE

    fun parse(source: FeedSource, body: String, now: Long): List<FeedItem> =
        if (source.kind == FeedKind.WIKIPEDIA) parseWikipedia(source, body, now) else parseXml(source, body, now)

    fun parseXml(source: FeedSource, xml: String, now: Long): List<FeedItem> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val seen = HashSet<String>()
        return doc.select("item, entry").asSequence().mapNotNull { e -> runCatching { entry(source, e, now) }.getOrNull() }
            .filter { seen.add(it.url) }.take(MAX_ITEMS).toList()
    }

    private fun Element.first(vararg names: String): String =
        names.firstNotNullOfOrNull { n -> getElementsByTag(n).firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()

    private fun entry(source: FeedSource, e: Element, now: Long): FeedItem? {
        val title = clean(e.first("title"), 300)
        val link = FeedItems.safeUrl(
            e.getElementsByTag("link").let { links ->
                (links.firstOrNull { it.attr("rel") == "alternate" || (it.hasAttr("href") && it.attr("rel").isEmpty()) }
                    ?: links.firstOrNull())?.let { it.attr("href").ifEmpty { it.text() } }
            } ?: e.first("guid"))
        if (title.isEmpty() || link.isEmpty()) return null
        val html = e.first("content:encoded", "description", "summary", "content")
        val image = FeedItems.safeUrl(
            e.getElementsByTag("media:thumbnail").firstOrNull()?.attr("url")
                ?: e.getElementsByTag("media:content").firstOrNull { it.attr("medium") == "image" || it.attr("type").startsWith("image") }?.attr("url")
                ?: e.getElementsByTag("enclosure").firstOrNull { it.attr("type").startsWith("image") }?.attr("url")
                ?: runCatching { Jsoup.parse(html).selectFirst("img[src]")?.attr("src") }.getOrNull())
        val published = date(e.first("pubDate", "published", "updated", "dc:date")) ?: now
        return FeedItem(FeedItems.idFor(link), source.id, source.language, source.topic, source.name, title,
            clean(html, 400), image, published.coerceAtMost(now), link)
    }

    private fun clean(html: String, max: Int): String {
        val text = Jsoup.parse(html).text().replace(Regex("\\s+"), " ").trim()
        return if (text.length > max) text.take(max - 1).trimEnd() + "…" else text
    }

    fun date(raw: String): Long? {
        if (raw.isBlank()) return null
        return runCatching { ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli() }.getOrNull()
    }

    /** `/feed/featured`: today's featured article and the most-read articles of the day. */
    fun parseWikipedia(source: FeedSource, json: String, now: Long): List<FeedItem> {
        val root = JSONObject(json)
        val rows = ArrayList<JSONObject>()
        root.optJSONObject("tfa")?.let { rows += it }
        root.optJSONObject("mostread")?.optJSONArray("articles")?.let { a ->
            for (i in 0 until a.length().coerceAtMost(12)) a.optJSONObject(i)?.let { rows += it }
        }
        val seen = HashSet<Long>()
        return rows.mapNotNull { r -> runCatching { wikiRow(source, r, now) }.getOrNull() }
            .filter { seen.add(it.wikiPageId) }.take(MAX_ITEMS)
    }

    private fun wikiRow(source: FeedSource, r: JSONObject, now: Long): FeedItem? {
        val id = r.optLong("pageid")
        val title = clean(r.optJSONObject("titles")?.optString("normalized")?.takeIf { it.isNotBlank() } ?: r.optString("title").replace('_', ' '), 300)
        val extract = clean(r.optString("extract"), 400)
        // Service pages (no pageid or extract: «Main Page», «Special:…») and disambiguations are not reading material.
        if (id <= 0 || title.isEmpty() || r.optString("type") == "disambiguation") return null
        if (extract.isEmpty()) return null
        val url = "https://${source.language}.wikipedia.org/?curid=$id"
        return FeedItem(FeedItems.idFor(url), source.id, source.language, source.topic, source.name, title, extract,
            FeedItems.safeUrl(r.optJSONObject("thumbnail")?.optString("source")), now, url, id)
    }
}
