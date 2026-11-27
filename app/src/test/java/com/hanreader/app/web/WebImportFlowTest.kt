package com.hanreader.app.web

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.LexiconDatabase
import com.hanreader.app.LexiconTheme
import com.hanreader.app.epub.BookFiles
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.lang.BookLanguages
import com.hanreader.app.lang.Languages
import com.hanreader.app.reader.PasteTextDialog
import com.hanreader.app.reader.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** A shared link -> the dialog -> a (mocked) page -> an EPUB in the library, reused for the same link. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class WebImportFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    private val dao get() = LexiconDatabase.get(app).bookDao()
    private val loads = AtomicInteger()
    private val article = WebArticle(
        url = "https://noticias.example.es/ciencia/rio", title = "El río vuelve a la ciudad", site = "Noticias de ejemplo",
        author = "Ana Pérez", language = "es",
        blocks = listOf(
            WebBlock("Durante años el río estuvo escondido bajo una avenida llena de coches."),
            WebBlock("Un nuevo parque", heading = 2),
            WebBlock("Ahora los vecinos pasean junto al agua y los niños buscan peces cerca de los puentes."),
        )
    )

    @Before fun setup() {
        Languages.current = Languages.SPANISH
        runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LexiconDatabase.get(app).clearAllTables() } }
    }
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }
    private fun vm(loader: suspend (String) -> WebArticle): ReaderViewModel =
        ReaderViewModel(app, null, 20_000L, { BookFiles.open(it) }, loader).also {
            compose.activity.viewModelStore.put("reader", it)
            it.contextAi.settings.update(it.contextAi.settings.config.value.copy(enabled = false, automatic = false))
        }
    private fun import(vm: ReaderViewModel, url: String): Result<Long> {
        var result: Result<Long>? = null
        vm.importUrl(url) { result = it }
        await { result != null }
        return result!!
    }

    @Test fun sharedLinkBecomesABookAndTheSameLinkReopensIt() {
        var link: String? = null
        compose.setContent { LexiconTheme {
            PasteTextDialog("Mira esto: https://noticias.example.es/ciencia/rio#comentarios",
                busy = false, error = null, onDismiss = {}, onSave = { _, _ -> fail("not a text") }, onLink = { link = it })
        } }
        compose.onNodeWithText("Загрузить статью").performClick()
        assertEquals("https://noticias.example.es/ciencia/rio", link)

        val vm = vm { loads.incrementAndGet(); article }
        val id = import(vm, link!!).getOrThrow()
        val row = runBlocking { dao.get(id) }!!
        assertEquals("El río vuelve a la ciudad", row.title)
        assertEquals("Ana Pérez", row.author)
        assertTrue(row.fileName.startsWith("web-") && row.fileName.endsWith(".epub"))
        assertEquals(Languages.SPANISH.tag, BookLanguages.of(app, id))
        BookFiles.open(File(File(app.filesDir, "books"), row.fileName)).use { book ->
            val texts = book.chapters.flatMap { book.blocks(it.index) }.filterIsInstance<EpubBlock.Text>()
            assertTrue(texts.any { it.heading && it.text == "Un nuevo parque" })
            assertTrue(texts.any { it.text.startsWith("Ahora los vecinos") })
        }

        assertEquals(id, import(vm, link!!).getOrThrow())
        assertEquals(1, loads.get())
        assertEquals(1, runBlocking { dao.observeAll().first().size })
    }

    @Test fun failuresAreExplainedAndNothingIsKept() {
        val offline = vm { throw java.io.IOException("offline") }
        val e = import(offline, "https://noticias.example.es/a").exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.startsWith("Не удалось загрузить страницу"))
        val empty = vm { throw WebArticleException(WebArticles.NO_TEXT) }
        assertEquals(WebArticles.NO_TEXT, import(empty, "https://noticias.example.es/b").exceptionOrNull()!!.message)
        assertEquals(0, runBlocking { dao.observeAll().first().size })
        assertTrue(File(app.filesDir, "books").listFiles().orEmpty().none { it.name.startsWith("web-") || it.name.endsWith(".tmp") })
    }
}
