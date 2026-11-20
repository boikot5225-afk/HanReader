package com.hanreader.app.feed

import org.junit.Assert.*
import org.junit.Test

class FeedParserTest {
    private val now = 1_800_000_000_000L
    private val rss = FeedSource("t-rss", "es", FeedTopic.WORLD, FeedKind.RSS, "Prueba", "https://example.org/rss", "test")
    private val wiki = FeedSource("t-wiki", "en", FeedTopic.ENCYCLOPEDIA, FeedKind.WIKIPEDIA, "Wikipedia", "https://en.wikipedia.org/x", "test")

    @Test fun rssItemsAreParsedAndUnsafeOnesDropped() {
        val xml = """<?xml version="1.0"?><rss xmlns:media="http://search.yahoo.com/mrss/"><channel>
            <item><title>Uno &amp; dos</title><link>https://example.org/a</link>
              <description>&lt;p&gt;Texto &lt;b&gt;largo&lt;/b&gt;&lt;/p&gt;</description>
              <pubDate>Fri, 20 Nov 2026 10:00:00 GMT</pubDate><media:thumbnail url="https://example.org/a.jpg"/></item>
            <item><title>Duplicado</title><link>https://example.org/a</link></item>
            <item><title>Sin https</title><link>http://example.org/b</link></item>
            <item><title></title><link>https://example.org/c</link></item>
            <item><title>Imagen insegura</title><link>https://example.org/d</link><enclosure url="http://x/y.jpg" type="image/jpeg"/></item>
        </channel></rss>"""
        val items = FeedParser.parseXml(rss, xml, now)
        assertEquals(listOf("Uno & dos", "Imagen insegura"), items.map { it.title })
        assertEquals("Texto largo", items[0].summary)
        assertEquals("https://example.org/a.jpg", items[0].imageUrl)
        assertEquals("", items[1].imageUrl)
        assertEquals(1_795_168_800_000L, items[0].publishedAt)
        assertFalse(items[0].opensInReader)
    }

    @Test fun atomEntriesUseAlternateLink() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Noticia</title>
            <link rel="self" href="https://example.org/self"/><link rel="alternate" href="https://example.org/n1"/>
            <summary>Resumen</summary><updated>2026-11-19T08:30:00Z</updated></entry></feed>"""
        val item = FeedParser.parseXml(rss, xml, now).single()
        assertEquals("https://example.org/n1", item.url)
        assertEquals("Resumen", item.summary)
        assertEquals(java.time.Instant.parse("2026-11-19T08:30:00Z").toEpochMilli(), item.publishedAt)
    }

    @Test fun futureAndMissingDatesAreClamped() {
        val xml = """<rss><channel><item><title>A</title><link>https://example.org/f</link><pubDate>Fri, 20 Nov 2090 10:00:00 GMT</pubDate></item>
            <item><title>B</title><link>https://example.org/g</link></item></channel></rss>"""
        assertTrue(FeedParser.parseXml(rss, xml, now).all { it.publishedAt == now })
    }

    @Test fun garbageDoesNotThrow() {
        assertTrue(FeedParser.parseXml(rss, "not xml at all", now).isEmpty())
    }

    @Test fun wikipediaFeedOpensInReader() {
        val json = """{"tfa":{"pageid":10,"titles":{"normalized":"Featured one"},"extract":"A <b>good</b> article.","thumbnail":{"source":"https://upload.wikimedia.org/a.jpg"}},
          "mostread":{"articles":[{"pageid":10,"title":"Featured_one","extract":"dup"},{"pageid":11,"title":"Second_page","extract":"Second."},
            {"title":"Special:Search"},{"pageid":12,"title":"Main_Page","extract":""},{"pageid":13,"title":"Mercury","type":"disambiguation","extract":"x"}]}}"""
        val items = FeedParser.parseWikipedia(wiki, json, now)
        assertEquals(listOf(10L, 11L), items.map { it.wikiPageId })
        assertEquals("A good article.", items[0].summary)
        assertEquals("Second page", items[1].title)
        assertTrue(items.all { it.opensInReader && it.url.startsWith("https://en.wikipedia.org/?curid=") })
    }

    @Test fun selectFiltersDeduplicatesAndSorts() {
        fun item(url: String, lang: String, topic: FeedTopic, at: Long) = FeedItem(FeedItems.idFor(url), "s", lang, topic, "S", "t", "", "", at, url)
        val all = listOf(
            item("https://a/1", "es", FeedTopic.WORLD, now - 1000), item("https://a/1", "es", FeedTopic.WORLD, now - 1000),
            item("https://a/2", "es", FeedTopic.WORLD, now - 500), item("https://a/3", "en", FeedTopic.WORLD, now),
            item("https://a/4", "es", FeedTopic.SPORT, now), item("https://a/5", "es", FeedTopic.WORLD, now - FeedItems.MAX_AGE_MS - 1))
        val shown = FeedItems.select(all, FeedSettings(setOf("es"), setOf(FeedTopic.WORLD)), now)
        assertEquals(listOf("https://a/2", "https://a/1"), shown.map { it.url })
    }

    @Test fun pruneBoundsTheCache() {
        val many = (0 until 100).map { FeedItem("i$it", "s1", "es", FeedTopic.WORLD, "S", "t", "", "", now - it, "https://a/$it") }
        val pruned = FeedItems.prune(mapOf("s1" to many, "s2" to emptyList()), now)
        assertEquals(setOf("s1"), pruned.keys)
        assertEquals(FeedItems.MAX_PER_SOURCE, pruned.getValue("s1").size)
    }

    @Test fun catalogIsHttpsOnlyWithUniqueIds() {
        assertTrue(FeedCatalog.sources.all { it.url.startsWith("https://") })
        assertEquals(FeedCatalog.sources.size, FeedCatalog.sources.map { it.id }.toSet().size)
        assertTrue(setOf("es", "en", "zh").all { it in FeedCatalog.languages() })
    }
}
