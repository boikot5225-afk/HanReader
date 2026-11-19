package com.hanreader.app.wiki

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Optional real, anonymous requests; never depends on an API key or private book text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WikipediaLiveTest {
    @Test fun officialApiSearchIntroAndRussianLanguageLink() = runBlocking {
        assumeTrue(System.getenv("HANREADER_LIVE_WIKIPEDIA") == "1")
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.noBackupFilesDir, "wikipedia-v1").deleteRecursively()
        val repo = WikipediaRepository(app)
        for ((language, query) in listOf("en" to "Cognitive dissonance", "es" to "Disonancia cognitiva", "zh" to "认知失调")) {
            val hits = repo.search(language, query)
            assertTrue(hits.isNotEmpty())
            val article = repo.article(language, hits.first().id)
            assertTrue(article.extract.length > 30); assertTrue(article.revision > 0)
            assertFalse(article.ambiguous)
            println("WIKIPEDIA_LIVE $language ${article.id} ${article.title} revision=${article.revision}")
            if (language == "en") {
                val russian = repo.translation(article, "ru")!!
                assertTrue(russian.title.contains("диссонанс", ignoreCase = true))
                assertTrue(russian.extract.length > 30)
                println("WIKIPEDIA_LIVE ru ${russian.id} ${russian.title} revision=${russian.revision}")
            }
        }
    }
}
