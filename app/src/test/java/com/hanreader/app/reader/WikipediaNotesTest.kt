package com.hanreader.app.reader

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.Languages
import com.hanreader.app.wiki.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WikipediaNotesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    private val article = WikiArticle("ru", 42, "Когнитивный диссонанс", "Внутренний конфликт представлений.", 321, 1234, false)
    @Before fun clearDatabase() { runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        LexiconDatabase.get(app).clearAllTables()
    } } }
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }
    private fun await(condition: () -> Boolean) { compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() } }

    @Test fun panelOnlySearchesOnTapAndSavesAnEditableDefinitionWithItsSource() {
        var searches = 0; var saved = ""
        val source = object : WikipediaSource {
            override suspend fun search(language: String, query: String): List<WikiHit> {
                searches++; assertEquals("Cognitive dissonance", query)
                return listOf(WikiHit(42, article.title, "Психология"))
            }
            override suspend fun article(language: String, id: Long) = article
            override suspend fun translation(article: WikiArticle, language: String) = null
        }
        val target = WikiNoteTarget(1, 0, "Chapter", 0, 0, 20, "Cognitive dissonance", "He studied cognitive dissonance.")
        compose.setContent { LexiconTheme { WikipediaSheet(target, "en-US", source,
            onSave = { selected, definition -> saved = wikipediaNote(target, selected, definition) }, onDismiss = {}) } }
        compose.waitForIdle(); assertEquals(0, searches)
        compose.onNodeWithText("Найти", substring = false).performScrollTo().performClick()
        await { searches == 1 }
        compose.onNodeWithText(article.title).performClick()
        compose.waitForIdle()
        val root = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/screens").mkdirs()
        java.io.File("build/screens/wikipedia-note.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.onNodeWithText("Описание для заметки").performTextReplacement("Моё краткое объяснение.")
        compose.onNodeWithText("Сохранить в заметки").performScrollTo().performClick()
        await { saved.isNotBlank() }
        assertTrue(saved.contains("Моё краткое объяснение."))
        assertTrue(saved.contains(article.revisionUrl))
        assertTrue(saved.contains("CC BY-SA 4.0")); assertTrue(saved.contains(target.sentence))
        assertTrue(saved.contains("отредактировано"))
        assertTrue(runCatching { wikipediaNote(target, article.copy(ambiguous = true), "Definition") }.isFailure)
    }
    @Test fun existingNotesArePreservedAndWikiNotePersistsInBackupAndAfterReopening() {
        Languages.current = Languages.ENGLISH
        val vm = ReaderViewModel(app)
        compose.activity.viewModelStore.put("reader", vm)
        var id: Long? = null
        vm.openDemoBook { id = it.getOrThrow() }; await { id != null }
        vm.openBook(id!!); await { vm.open.value?.let { !it.loading && it.chapter != null } == true }
        val chapter = vm.open.value!!.chapter!!
        val blockIndex = chapter.blocks.indexOfFirst { it is ReaderBlock.Text && !it.heading }
        val block = chapter.blocks[blockIndex] as ReaderBlock.Text
        val token = block.tokens.first { it.text.any(Char::isLetter) }
        val target = WikiNoteTarget(id!!, chapter.index, chapter.title, blockIndex, token.start, token.end,
            block.shown.substring(token.start, token.end), block.shown)
        val dao = LexiconDatabase.get(app).highlightDao()
        vm.addHighlight(chapter, blockIndex, token.start, token.end, 0, "Моя старая заметка")
        await { runBlocking { dao.all().any { it.bookId == id && it.note == "Моя старая заметка" } } }
        runBlocking { vm.saveWikipediaNote(target, article, article.extract) }
        runBlocking { vm.saveWikipediaNote(target, article, article.extract) }
        val rows = runBlocking { dao.all().filter { it.bookId == id && it.startChar == token.start && it.blockIndex == blockIndex } }
        assertEquals(1, rows.size)
        val note = rows.single()
        assertTrue(note.note.startsWith("Моя старая заметка"))
        assertEquals(1, Regex("Источник: Википедия").findAll(note.note).count())
        val markdown = Highlights.markdown("Book", "Author", rows)
        assertTrue(markdown.contains(article.revisionUrl)); assertTrue(markdown.contains(target.term))
        val book = vm.open.value!!.entity
        val backup = Backup(1, emptyList(), emptyList(), emptyList(), library = LibraryBackup(listOf(book),
            emptyList(), rows, emptyList(), languages = mapOf(book.id to Languages.ENGLISH.tag)))
        assertEquals(rows.map { it.copy(id = 0) }, BackupFormat.decode(BackupFormat.encode(backup)).library!!.highlights)
        vm.closeBook(); vm.openBook(id!!)
        await { vm.open.value?.let { !it.loading && it.chapter != null } == true }
        assertEquals(note, runBlocking { dao.all().first { it.id == note.id } })
        vm.closeBook()
        assertTrue(runCatching { runBlocking { vm.saveWikipediaNote(target, article, "Text") } }.isFailure)
    }
    @Test fun phraseSelectionOffersWikipediaAndNetworkErrorsNeverSaveANote() {
        var opened = false
        compose.setContent { LexiconTheme { PhraseSheet("cognitive dissonance", "", null, false,
            onSentence = {}, onColor = { _, _, _ -> }, onNote = { _, _ -> }, onRemove = {}, onDismiss = {},
            onWikipedia = { opened = true }) } }
        compose.onNodeWithText("Найти в Википедии").performClick()
        assertTrue(opened)
    }
    @Test fun failedSearchCanBeRetriedWithoutAutomaticRequestsOrSaves() {
        var calls = 0; var saves = 0
        val source = object : WikipediaSource {
            override suspend fun search(language: String, query: String): List<WikiHit> { calls++; error("private request detail") }
            override suspend fun article(language: String, id: Long) = error("unused")
            override suspend fun translation(article: WikiArticle, language: String): WikiArticle? = null
        }
        compose.setContent { LexiconTheme { WikipediaSheet(WikiNoteTarget(1, 0, "Chapter", 0, 0, 4, "term", "secret book sentence"),
            "es-ES", source, { _, _ -> saves++ }, {}) } }
        compose.onNodeWithText("Найти", substring = false).performScrollTo().performClick()
        await { calls == 1 }
        compose.onNodeWithText("Не удалось получить данные Википедии.", substring = true).assertExists()
        compose.onNodeWithText("private request detail").assertDoesNotExist()
        compose.onNodeWithText("Сохранить в заметки").assertDoesNotExist()
        assertEquals(0, saves)
        compose.onNodeWithText("Найти", substring = false).performScrollTo().performClick()
        await { calls == 2 }; assertEquals(0, saves)
    }
}
