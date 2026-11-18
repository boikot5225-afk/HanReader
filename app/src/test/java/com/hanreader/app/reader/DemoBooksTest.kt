package com.hanreader.app.reader

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.epub.EpubBook
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.lang.BookLanguages
import com.hanreader.app.lang.LanguageInfo
import com.hanreader.app.lang.Languages
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoBooksTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    private val dao get() = LexiconDatabase.get(app).bookDao()
    @Before fun emptyLibrary() {
        runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LexiconDatabase.get(app).clearAllTables()
        } }
    }
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }
    private fun vm(key: String = "reader"): ReaderViewModel = ReaderViewModel(app).also {
        compose.activity.viewModelStore.put(key, it)
        it.contextAi.settings.update(it.contextAi.settings.config.value.copy(enabled = false, automatic = false))
    }
    private fun add(vm: ReaderViewModel): Long {
        var result: Result<Long>? = null
        vm.openDemoBook { result = it }
        await { result != null }
        return result!!.getOrThrow()
    }
    private fun capture(name: String) {
        val root = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun bundledEpubsHaveCompleteTextContentsAndTheCorrectLanguage() {
        assertEquals(Languages.ALL.map { it.tag }.toSet(), DemoBooks.all.map { it.tag }.toSet())
        for (sample in DemoBooks.all) {
            val copy = File(app.cacheDir, sample.fileName)
            app.assets.open(sample.asset).use { input -> copy.outputStream().use { input.copyTo(it) } }
            EpubBook(copy).use { book ->
                assertEquals(sample.title, book.title)
                assertEquals(3, book.chapters.size)
                assertEquals(3, book.toc.size)
                assertEquals(sample.tag, BookLanguages.recognize(book).tag)
                book.chapters.forEach { c ->
                    val texts = book.blocks(c.index).filterIsInstance<EpubBlock.Text>()
                    assertTrue(texts.any { it.heading })
                    assertTrue(texts.filter { !it.heading }.sumOf { it.text.length } > 100)
                }
            }
        }
    }

    private fun opensFromLibrary(language: LanguageInfo) {
        Languages.current = language
        AppearanceStore.load(app)
        AppearanceStore.update { it.copy(mode = ReadingMode.PAGES, gloss = GlossMode.UNKNOWN) }
        val vm = vm()
        var reading by mutableStateOf(false)
        compose.setContent { LexiconTheme {
            if (reading) ReaderScreen(vm) { vm.closeBook(); reading = false }
            else LibraryScreen(vm, PaddingValues(), onOpen = { vm.openBook(it); reading = true }, onStats = {})
        } }
        // Adding nothing is deliberate: installing/updating the app must not populate a library.
        assertTrue(runBlocking { dao.all() }.isEmpty())
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Читать пробную книгу"))
        capture("demo-library-${language.tag}")
        compose.onNodeWithText("Читать пробную книгу").performClick()
        await { vm.open.value?.let { !it.loading && it.chapter != null } == true && vm.engine.value != null }
        await { compose.onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        await { vm.aiReady.value } // Chinese first opens with its core before Russian meanings load.
        compose.waitForIdle()
        capture("demo-reader-${language.tag}")
        val open = vm.open.value!!
        assertEquals(DemoBooks.forLanguage(language.tag)!!.title, open.entity.title)
        assertEquals(language.tag, BookLanguages.of(app, open.entity.id))
        val block = open.chapter!!.blocks.filterIsInstance<ReaderBlock.Text>().first { !it.heading }
        val engine = vm.engine.value!!
        val token = block.tokens.first { engine.glossShort(it)?.any { c -> c in 'А'..'я' || c == 'ё' } == true }
        val meaning = engine.glossShort(token)!!
        var saved: Result<Boolean>? = null
        vm.saveWord(ReaderSave(engine.lemma(token.text), engine.reading(token.text).orEmpty(), meaning,
            block.text, open.entity.id, open.entity.title, 0, open.chapter!!.title)) { saved = it }
        await { saved != null }
        assertTrue(saved!!.getOrThrow())
        assertEquals(0, vm.contextAi.repository.usage.value.requests)
        assertEquals(1, runBlocking { dao.all() }.size)
        assertNull(vm.open.value!!.error)
    }
    @Test fun spanishDemoOpensOfflineFromTheLibraryWithDictionaryAndCards() = opensFromLibrary(Languages.SPANISH)
    @Test fun englishDemoOpensOfflineFromTheLibraryWithDictionaryAndCards() = opensFromLibrary(Languages.ENGLISH)
    @Test fun chineseDemoOpensOfflineFromTheLibraryWithDictionaryAndCards() = opensFromLibrary(Languages.CHINESE)

    @Test fun repeatedAndConcurrentOpeningKeepsOneBookAndProgressAcrossViewModels() {
        Languages.current = Languages.ENGLISH
        val first = vm()
        val parallel = vm("parallel")
        var a: Result<Long>? = null; var b: Result<Long>? = null
        first.openDemoBook { a = it }; parallel.openDemoBook { b = it }
        await { a != null && b != null }
        val id = a!!.getOrThrow()
        assertEquals(id, b!!.getOrThrow())
        runBlocking { dao.savePosition(id, 1, 2, 3) }
        first.closeBook()
        val next = vm("next")
        assertEquals(id, add(next))
        assertEquals(1, runBlocking { dao.all() }.size)
        next.openBook(id)
        await { next.open.value?.let { !it.loading && it.chapter?.index == 1 } == true }
        assertEquals(2, next.open.value!!.entity.blockIndex)
        assertEquals(3, next.open.value!!.entity.charOffset)
        val book = runBlocking { dao.get(id)!! }
        val backup = Backup(1, emptyList(), emptyList(), emptyList(), library = LibraryBackup(
            listOf(book), emptyList(), emptyList(), emptyList(), languages = mapOf(id to Languages.ENGLISH.tag)))
        val restored = BackupFormat.decode(BackupFormat.encode(backup)).library!!.books.single()
        assertEquals(book, restored)
    }

    @Test fun removedFileIsRestoredWithTheSameIdAndDeletedBookCanBeAddedAgain() {
        Languages.current = Languages.SPANISH
        val vm = vm()
        val id = add(vm)
        runBlocking { dao.savePosition(id, 1, 1, 2) }
        val book = runBlocking { dao.get(id)!! }
        val file = File(app.filesDir, "books/${book.fileName}")
        assertTrue(file.delete())
        assertEquals(id, add(vm))
        assertTrue(file.isFile)
        assertEquals(1, runBlocking { dao.get(id)!!.chapterIndex })
        vm.deleteBook(book)
        await { runBlocking { dao.get(id) == null } }
        val newId = add(vm)
        assertNotEquals(id, newId)
        assertEquals(1, runBlocking { dao.all() }.size)
    }
}
