package com.hanreader.app.wiki

import android.graphics.Bitmap
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WikiImageStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val url = "https://upload.wikimedia.org/wikipedia/commons/a/ab/Photo.png"
    private val image = WikiImage(url, "File:Photo.png", "Подпись", 200, 100, 0)
    private val info = WikiImageInfo(url, "https://commons.wikimedia.org/wiki/File:Photo.png", "Автор", "CC BY-SA 3.0")
    private fun png(): ByteArray = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).let { bitmap ->
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }
    @Test fun duplicateRequestsAreSerializedAndPinnedImageSurvivesAProcessRestartOffline() = runBlocking {
        val cache = folder.newFolder("cache"); val pins = folder.newFolder("pins")
        var calls = 0
        val store = WikiImageStore(cache, pins, { _, _ -> info }, { calls++; delay(20); png() })
        coroutineScope { listOf(async { store.load("es", image) }, async { store.load("es", image) }).awaitAll() }
        assertEquals(1, calls)
        store.pin("es", image)
        cache.deleteRecursively()
        val reopened = WikiImageStore(cache, pins, { _, _ -> error("offline") }, { error("offline") })
        assertEquals(info, reopened.load("es", image).info)
        reopened.prune(setOf(url)); assertEquals(1, pins.listFiles()!!.size)
        reopened.prune(emptySet()); assertTrue(pins.listFiles()!!.isEmpty())
        assertTrue(runCatching { reopened.load("es", image) }.isFailure)
    }
    @Test fun invalidImageCannotBePinnedAndLicenseHtmlIsInert() = runBlocking {
        val cache = folder.newFolder("cache"); val pins = folder.newFolder("pins")
        val store = WikiImageStore(cache, pins, { _, _ -> info }, { "<svg><script>bad()</script></svg>".toByteArray() })
        assertTrue(runCatching { store.pin("es", image) }.isFailure)
        assertTrue(pins.listFiles()!!.isEmpty()); assertTrue(cache.listFiles()!!.isEmpty())
        val row = JSONObject().put("thumburl", "$url?utm_source=en.wikipedia.org&utm_campaign=imageinfo").put("descriptionurl", info.page).put("extmetadata", JSONObject()
            .put("Artist", JSONObject().put("value", "<a href='https://evil.example'>Автор</a>"))
            .put("LicenseShortName", JSONObject().put("value", "CC BY-SA 3.0")))
        val parsed = WikiImages.metadata(JSONObject().put("query", JSONObject().put("pages", org.json.JSONArray().put(JSONObject().put("imageinfo", org.json.JSONArray().put(row))))))
        assertEquals(info, parsed)
        row.put("thumburl", "https://evil.example/tracker.png")
        assertTrue(runCatching { WikiImages.metadata(JSONObject().put("query", JSONObject().put("pages", org.json.JSONArray().put(JSONObject().put("imageinfo", org.json.JSONArray().put(row)))))) }.isFailure)
    }
    @Test fun repositoryReportsPartialPinsAndDeletingOneArticleKeepsSharedImages() = runBlocking {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        java.io.File(app.filesDir, "wikipedia-reading-v1").deleteRecursively()
        java.io.File(app.noBackupFilesDir, "wikipedia-v1").deleteRecursively()
        java.io.File(app.noBackupFilesDir, "wikipedia-images-v1").deleteRecursively()
        val row = JSONObject().put("thumburl", "$url?utm_source=en.wikipedia.org&utm_campaign=imageinfo").put("descriptionurl", info.page).put("extmetadata", JSONObject()
            .put("Artist", JSONObject().put("value", "Автор")).put("LicenseShortName", JSONObject().put("value", "CC BY-SA 3.0")))
        val response = JSONObject().put("query", JSONObject().put("pages", org.json.JSONArray().put(JSONObject().put("imageinfo", org.json.JSONArray().put(row))))).toString()
        var downloads = 0
        val repo = WikipediaRepository(app, { address -> check(!address.contains("Missing")); response }, fetchImage = { downloads++; png() })
        val article = WikiArticle("en", 42, "Science", "Intro.", 321, 1234, false)
        val missing = image.copy(url = url.replace("Photo", "Missing"), title = "File:Missing.png")
        val first = WikiDocument(article, listOf(WikiBlock("Text.")), listOf(image, missing))
        val second = first.copy(article = article.copy(id = 43), images = listOf(image))
        repo.saveArticle(first); repo.saveArticle(second)
        assertEquals(WikiImageSaveResult(1, 2), repo.saveImages(first))
        assertEquals(WikiImageSaveResult(1, 1), repo.saveImages(second))
        assertEquals(1, downloads)
        java.io.File(app.noBackupFilesDir, "wikipedia-images-v1").deleteRecursively()
        val offline = WikipediaRepository(app, { error("offline") }, fetchImage = { error("offline") })
        offline.removeSavedArticle(first.article)
        assertEquals(info, offline.image(second.article, image).info)
        assertEquals(second, offline.fullArticle(second.article))
        offline.removeSavedArticle(second.article)
        assertTrue(runCatching { offline.image(second.article, image) }.isFailure)
    }

}
