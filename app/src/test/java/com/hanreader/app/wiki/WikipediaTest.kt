package com.hanreader.app.wiki

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WikipediaTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    @Before fun clearCache() { File(app.noBackupFilesDir, "wikipedia-v1").deleteRecursively() }
    private val search = """{"query":{"search":[{"pageid":42,"title":"Cognitive dissonance","snippet":"A <span class='searchmatch'>psychological</span> concept"}]}}"""
    private fun article(title: String = "Cognitive dissonance", extra: String = "") =
        """{"query":{"pages":[{"pageid":42,"title":"$title","extract":"A psychological concept.","revisions":[{"revid":321}]$extra}]}}"""

    @Test fun searchAndArticleSurviveRestartWithoutNetworkAndDoNotSendBookContext() = runBlocking {
        val urls = mutableListOf<String>()
        val repo = WikipediaRepository(app, { url -> urls += url; if ("list=search" in url) search else article() }, { 1234 })
        assertEquals("A psychological concept", repo.search("en", "Cognitive dissonance").single().snippet)
        val loaded = repo.article("en", 42)
        assertEquals(321L, loaded.revision)
        assertEquals(1234L, loaded.fetchedAt)
        assertEquals("https://en.wikipedia.org/w/index.php?oldid=321", loaded.revisionUrl)
        val offline = WikipediaRepository(app, { error("No network") })
        assertEquals(42L, offline.search("en", "Cognitive dissonance").single().id)
        assertEquals(loaded, offline.article("en", 42))
        assertEquals(2, urls.size)
        assertTrue(urls.all { it.startsWith("https://en.wikipedia.org/w/api.php?") })
        assertFalse(urls.any { "book" in it || "context" in it || "key=" in it })
    }
    @Test fun concurrentSearchIsDeduplicatedAndInvalidRepliesAreNotCached() = runBlocking {
        var calls = 0
        val repo = WikipediaRepository(app, { calls++; delay(10); search })
        val results = awaitAll(async { repo.search("ru", "поле") }, async { repo.search("ru", "поле") })
        assertEquals(results[0], results[1]); assertEquals(1, calls)
        val bad = WikipediaRepository(app, { "{truncated" })
        assertTrue(runCatching { bad.article("en", 77) }.isFailure)
        var repaired = 0
        val good = WikipediaRepository(app, { repaired++; article() })
        good.article("en", 77)
        assertEquals(1, repaired)
        assertTrue(runCatching { good.search("evil.example", "term") }.isFailure)
        assertTrue(runCatching { good.search("en", " ") }.isFailure)
        assertEquals(1, repaired)
    }
    @Test fun linkedRussianArticleUsesLanguageLinksAndAmbiguousPagesRemainMarked() = runBlocking {
        val urls = mutableListOf<String>()
        val repo = WikipediaRepository(app, { url ->
            urls += url
            when {
                "langlinks" in url -> """{"query":{"pages":[{"pageid":42,"langlinks":[{"lang":"ru","title":"Когнитивный диссонанс"}]}]}}"""
                url.startsWith("https://ru.") -> article("Когнитивный диссонанс")
                else -> article(extra = ",\"pageprops\":{\"disambiguation\":\"\"}")
            }
        })
        val ambiguous = repo.article("en", 42)
        assertTrue(ambiguous.ambiguous)
        val translated = repo.translation(ambiguous, "ru")!!
        assertEquals("ru", translated.language)
        assertEquals("Когнитивный диссонанс", translated.title)
        assertTrue(urls.any { "lllang=ru" in it })
        assertTrue(urls.any { "titles=" in it && it.startsWith("https://ru.") })
    }
}
