package com.hanreader.app.feed

import java.security.MessageDigest

enum class FeedTopic(val title: String) {
    WORLD("Мир"), SCIENCE("Наука"), TECH("Технологии"), CULTURE("Культура"),
    SPORT("Спорт"), TRAVEL("Путешествия"), HISTORY("История"), ENCYCLOPEDIA("Энциклопедия")
}

enum class FeedKind {
    /** Wikipedia's daily «featured» feed: items open in the in-app reader. */
    WIKIPEDIA,
    /** RSS/Atom: only title, short summary and link are shown; the article opens in the browser. */
    RSS
}

data class FeedSource(
    val id: String, val language: String, val topic: FeedTopic, val kind: FeedKind,
    val name: String, val url: String, val license: String
)

data class FeedItem(
    val id: String, val sourceId: String, val language: String, val topic: FeedTopic,
    val sourceName: String, val title: String, val summary: String, val imageUrl: String,
    val publishedAt: Long, val url: String, val wikiPageId: Long = 0
) {
    val opensInReader get() = wikiPageId > 0
}

data class FeedSettings(
    val languages: Set<String>, val topics: Set<FeedTopic> = FeedTopic.values().toSet(), val wifiOnly: Boolean = false
)

object FeedItems {
    const val MAX_PER_SOURCE = 40
    const val MAX_TOTAL = 400
    const val MAX_AGE_MS = 14L * 24 * 3600 * 1000

    fun idFor(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** Only HTTPS links are accepted anywhere in the feed. */
    fun safeUrl(url: String?): String {
        val u = url?.trim().orEmpty()
        return if (u.length <= 2000 && u.startsWith("https://") && u.none { it.isWhitespace() }) u else ""
    }

    /** Items for the screen: selected languages/topics, no duplicate links, newest first. */
    fun select(items: Collection<FeedItem>, settings: FeedSettings, now: Long): List<FeedItem> =
        items.asSequence()
            .filter { it.language in settings.languages && it.topic in settings.topics && now - it.publishedAt <= MAX_AGE_MS }
            .distinctBy { it.url }
            .sortedByDescending { it.publishedAt }
            .toList()

    /** Cache bound: fresh items only, per-source and total limits. */
    fun prune(bySource: Map<String, List<FeedItem>>, now: Long): Map<String, List<FeedItem>> {
        val kept = bySource.mapValues { (_, list) ->
            list.filter { now - it.publishedAt <= MAX_AGE_MS }.sortedByDescending { it.publishedAt }.take(MAX_PER_SOURCE)
        }.filterValues { it.isNotEmpty() }
        var total = 0
        val result = LinkedHashMap<String, List<FeedItem>>()
        for ((id, list) in kept.entries.sortedByDescending { it.value.first().publishedAt }) {
            val room = MAX_TOTAL - total
            if (room <= 0) break
            val part = list.take(room); total += part.size; result[id] = part
        }
        return result
    }
}

/** Open sources only. Wikipedia text is CC BY-SA; news feeds contribute headlines and links. */
object FeedCatalog {
    private const val WIKI = "Википедия, CC BY-SA 4.0"
    private const val BBC = "BBC: заголовки и ссылки из открытой RSS-ленты"
    private const val DW = "Deutsche Welle: заголовки и ссылки из открытой RSS-ленты"
    private const val WIKINEWS = "Викиновости, CC BY 2.5"
    private const val GOOGLE = "Google News RSS: заголовки и ссылки из открытой RSS-ленты"

    private fun bbcNews(l: String, topic: FeedTopic, path: String, name: String) =
        FeedSource("bbc-$l-${topic.name.lowercase()}", l, topic, FeedKind.RSS, name, "https://feeds.bbci.co.uk/$path/rss.xml", BBC)

    val sources: List<FeedSource> = listOf(
        FeedSource("wiki-es", "es", FeedTopic.ENCYCLOPEDIA, FeedKind.WIKIPEDIA, "Википедия", "https://es.wikipedia.org/api/rest_v1/feed/featured/{date}", WIKI),
        FeedSource("wiki-en", "en", FeedTopic.ENCYCLOPEDIA, FeedKind.WIKIPEDIA, "Википедия", "https://en.wikipedia.org/api/rest_v1/feed/featured/{date}", WIKI),
        FeedSource("wiki-zh", "zh", FeedTopic.ENCYCLOPEDIA, FeedKind.WIKIPEDIA, "Википедия", "https://zh.wikipedia.org/api/rest_v1/feed/featured/{date}", WIKI),
        FeedSource("wikinews-es", "es", FeedTopic.WORLD, FeedKind.RSS, "Викиновости", "https://es.wikinews.org/w/index.php?title=Special:NewsFeed&feed=atom&categories=Publicado&count=20", WIKINEWS),
        FeedSource("wikinews-en", "en", FeedTopic.WORLD, FeedKind.RSS, "Wikinews", "https://en.wikinews.org/w/index.php?title=Special:NewsFeed&feed=atom&categories=Published&count=20", WIKINEWS),
        FeedSource("wikinews-zh", "zh", FeedTopic.WORLD, FeedKind.RSS, "維基新聞", "https://zh.wikinews.org/w/index.php?title=Special:NewsFeed&feed=atom&categories=Published&count=20", WIKINEWS),
        FeedSource("google-es-world", "es", FeedTopic.WORLD, FeedKind.RSS, "Google News (ES)", "https://news.google.com/rss?hl=es-419&gl=US&ceid=US:es-419", GOOGLE),
        FeedSource("google-en-world", "en", FeedTopic.WORLD, FeedKind.RSS, "Google News (EN)", "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en", GOOGLE),
        FeedSource("google-zh-world", "zh", FeedTopic.WORLD, FeedKind.RSS, "Google 新闻", "https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans", GOOGLE),
        bbcNews("es", FeedTopic.WORLD, "mundo", "BBC Mundo"),
        bbcNews("zh", FeedTopic.WORLD, "zhongwen/simp", "BBC 中文"),
        bbcNews("en", FeedTopic.WORLD, "news/world", "BBC News: World"),
        bbcNews("en", FeedTopic.SCIENCE, "news/science_and_environment", "BBC News: Science"),
        bbcNews("en", FeedTopic.TECH, "news/technology", "BBC News: Technology"),
        bbcNews("en", FeedTopic.CULTURE, "news/entertainment_and_arts", "BBC News: Culture"),
        bbcNews("en", FeedTopic.SPORT, "sport", "BBC Sport"),
        FeedSource("dw-es-world", "es", FeedTopic.WORLD, FeedKind.RSS, "DW Español", "https://rss.dw.com/rdf/rss-sp-all", DW),
        FeedSource("dw-zh-world", "zh", FeedTopic.WORLD, FeedKind.RSS, "DW 中文", "https://rss.dw.com/rdf/rss-chi-all", DW),
        FeedSource("dw-en-world", "en", FeedTopic.WORLD, FeedKind.RSS, "DW English", "https://rss.dw.com/rdf/rss-en-all", DW)
    )
    fun byId(id: String) = sources.firstOrNull { it.id == id }
    fun languages(): List<String> = sources.map { it.language }.distinct()
    fun topicsFor(languages: Set<String>): List<FeedTopic> =
        sources.filter { it.language in languages }.map { it.topic }.distinct()
}
