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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class WikipediaLiveTest {
    @Test fun officialApiSearchIntroAndRussianLanguageLink() = runBlocking {
        assumeTrue(System.getenv("HANREADER_LIVE_WIKIPEDIA") == "1")
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.noBackupFilesDir, "wikipedia-v1").deleteRecursively()
        File(app.filesDir, "wikipedia-reading-v1").deleteRecursively()
        val repo = WikipediaRepository(app)
        for ((language, query) in listOf("en" to "Cognitive dissonance", "es" to "Disonancia cognitiva", "zh" to "认知失调")) {
            val hits = repo.search(language, query)
            assertTrue(hits.isNotEmpty())
            val article = repo.article(language, hits.first().id)
            assertTrue(article.extract.length > 30); assertTrue(article.revision > 0)
            assertFalse(article.ambiguous)
            println("WIKIPEDIA_LIVE $language ${article.id} ${article.title} revision=${article.revision}")
            val document = repo.fullArticle(article)
            assertTrue(document.blocks.size > 5)
            if (language == "en") assertTrue("Illustrated live article lost all images", document.images.isNotEmpty())
            assertTrue(document.blocks.any { it.heading > 0 })
            repo.saveArticle(document)
            document.images.firstOrNull()?.let { image ->
                val asset = repo.image(article, image)
                assertTrue(asset.bytes.isNotEmpty()); assertTrue(asset.info.page.startsWith("https://"))
                assertEquals(WikiImageSaveResult(1, 1), repo.saveImages(document.copy(images = listOf(image))))
                File(app.noBackupFilesDir, "wikipedia-images-v1").deleteRecursively()
                val offlineImages = WikipediaRepository(app, { error("Offline") }, fetchImage = { error("Offline") })
                assertEquals(asset.info, offlineImages.image(article, image).info)
                println("WIKIPEDIA_IMAGE_LIVE $language images=${document.images.size} bytes=${asset.bytes.size} license=${asset.info.license} offline=true")
            }
            val offline = WikipediaRepository(app, { error("Offline") })
            assertEquals(document, offline.fullArticle(document.article))
            println("WIKIPEDIA_FULL_LIVE $language blocks=${document.blocks.size} chars=${document.blocks.sumOf { it.text.length }} revision=${document.article.revision}")
            if (language == "en") {
                val russian = repo.translation(article, "ru")!!
                assertTrue(russian.title.contains("диссонанс", ignoreCase = true))
                assertTrue(russian.extract.length > 30)
                assertTrue(repo.fullArticle(russian).blocks.size > 5)
                println("WIKIPEDIA_LIVE ru ${russian.id} ${russian.title} revision=${russian.revision}")
            }
        }
    }
}
