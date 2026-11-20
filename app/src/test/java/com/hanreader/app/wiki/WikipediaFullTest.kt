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
class WikipediaFullTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val article = WikiArticle("en", 42, "Science", "The introduction.", 321, 1234, false)
    private val html = """{"parse":{"pageid":42,"revid":321,"text":"<p>The introduction.</p><h2>History</h2><p>Full text after the introduction.</p>"}}"""
    @Before fun clear() {
        File(app.noBackupFilesDir, "wikipedia-v1").deleteRecursively()
        File(app.filesDir, "wikipedia-reading-v1").deleteRecursively()
    }
    @Test fun fullRevisionIsDeduplicatedCachedAndPinnedOfflineAcrossRestartAndCacheRemoval() = runBlocking {
        var calls = 0
        val urls = mutableListOf<String>()
        val repo = WikipediaRepository(app, { url -> calls++; urls += url; delay(10); html }, { 1234 })
        val docs = awaitAll(async { repo.fullArticle(article) }, async { repo.fullArticle(article) })
        assertEquals(1, calls); assertEquals(docs[0], docs[1])
        assertTrue(urls.single().contains("action=parse")); assertTrue(urls.single().contains("oldid=321"))
        assertFalse(urls.single().contains("book")); assertFalse(urls.single().contains("key="))
        repo.saveArticle(docs[0]); repo.saveArticle(docs[0])
        assertEquals(1, repo.savedArticles().size)
        File(app.noBackupFilesDir, "wikipedia-v1").deleteRecursively()
        val offline = WikipediaRepository(app, { error("No network") })
        val saved = offline.savedArticles().single()
        assertEquals(docs[0], offline.fullArticle(saved))
        offline.removeSavedArticle(saved)
        assertTrue(offline.savedArticles().isEmpty())
        assertTrue(runCatching { offline.fullArticle(saved) }.isFailure)
    }
    @Test fun invalidOrChangedRevisionsAreNotCachedAndAConnectionFailureDoesNotDamageASavedArticle() = runBlocking {
        val repo = WikipediaRepository(app, { html }, { 1234 })
        repo.saveArticle(repo.fullArticle(article))
        assertTrue(runCatching { repo.fullArticle(article.copy(revision = 999)) }.isFailure)
        var freshCalls = 0
        val later = WikipediaRepository(app, { freshCalls++; html.replace("321", "999") }, { 5678 })
        assertEquals(999, later.fullArticle(article.copy(revision = 999)).article.revision.toInt())
        assertEquals(1, freshCalls)
        val broken = WikipediaRepository(app, { "{invalid" })
        assertTrue(runCatching { broken.fullArticle(article.copy(revision = 1000)) }.isFailure)
        assertEquals(321L, broken.savedArticles().single().revision)
        assertEquals(article, broken.fullArticle(article).article)
    }
    @Test fun aDamagedOfflineFileCanBeRecoveredFromTheValidCacheAndExplicitlySavedAgain() = runBlocking {
        var calls = 0
        val repo = WikipediaRepository(app, { calls++; html }, { 1234 })
        val original = repo.fullArticle(article); repo.saveArticle(original)
        File(app.filesDir, "wikipedia-reading-v1/en-42-321.json").writeText("{broken")
        assertTrue(repo.savedArticles().isEmpty())
        val recovered = repo.fullArticle(article)
        assertEquals(original, recovered); assertEquals(1, calls)
        repo.saveArticle(recovered)
        assertEquals(listOf(article), repo.savedArticles())
    }
}
