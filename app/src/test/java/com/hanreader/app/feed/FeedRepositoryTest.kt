package com.hanreader.app.feed

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedRepositoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val now = java.time.Instant.parse("2026-11-20T12:00:00Z").toEpochMilli()
    @Before fun clean() {
        File(app.noBackupFilesDir, "feed-v1").deleteRecursively()
        app.getSharedPreferences("feed-v1", 0).edit().clear().commit()
    }
    private fun rss(title: String, n: Int) = """<rss><channel><item><title>$title</title><link>https://example.org/$n</link>
        <pubDate>Fri, 20 Nov 2026 10:00:00 GMT</pubDate></item></channel></rss>"""
    private val wikiJson = """{"tfa":{"pageid":7,"titles":{"normalized":"Tema"},"extract":"Texto."},"mostread":{"articles":[]}}"""

    @Test fun refreshFetchesOnlySelectedSourcesOverHttpsAndSurvivesOffline() = runBlocking {
        val urls = mutableListOf<String>()
        val repo = FeedRepository(app, { url -> urls += url; if ("wikipedia" in url) wikiJson else rss("Noticia", urls.size) }, { now })
        val settings = FeedSettings(setOf("es"), setOf(FeedTopic.WORLD, FeedTopic.ENCYCLOPEDIA))
        val result = repo.refresh(settings)
        assertTrue(result.failed.isEmpty())
        assertTrue(urls.all { it.startsWith("https://") })
        assertTrue(urls.none { "/en." in it || "//en." in it || "zhongwen" in it })
        assertTrue(urls.any { "/feed/featured/2026/11/19" in it })
        val offline = FeedRepository(app, { error("No network") }, { now })
        val shown = offline.cached(settings)
        assertTrue(shown.any { it.opensInReader && it.wikiPageId == 7L })
        assertTrue(shown.all { it.language == "es" })
    }

    @Test fun oneBrokenSourceDoesNotHideOthersAndOldItemsStay() = runBlocking {
        val good = FeedRepository(app, { rss("Antes", 1) }, { now })
        val settings = FeedSettings(setOf("en"), setOf(FeedTopic.SPORT))
        good.refresh(settings)
        val broken = FeedRepository(app, { error("down") }, { now })
        val result = broken.refresh(settings)
        assertEquals(result.attempted, result.failed.size)
        assertEquals(1, broken.cached(settings).size)
        broken.clear()
        assertTrue(broken.cached(settings).isEmpty())
    }

    @Test fun settingsAndReadMarksPersist() {
        val repo = FeedRepository(app)
        assertEquals(setOf("zh"), repo.settings("zh").languages)
        repo.save(FeedSettings(setOf("es", "en"), setOf(FeedTopic.SCIENCE), true))
        val loaded = FeedRepository(app).settings("zh")
        assertEquals(setOf("es", "en"), loaded.languages)
        assertEquals(setOf(FeedTopic.SCIENCE), loaded.topics)
        assertTrue(loaded.wifiOnly)
        repo.markRead("a"); repo.markRead("b")
        assertEquals(setOf("a", "b"), FeedRepository(app).readIds())
    }
}
