package com.hanreader.app.reader

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.Languages
import com.hanreader.app.wiki.WikiArticle
import kotlinx.coroutines.*
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
class BookKnowledgeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db get() = LexiconDatabase.get(app)
    private val previous = Languages.current
    private lateinit var vm: ReaderViewModel
    private fun await(condition: () -> Boolean) { compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() } }
    @Before fun setup() {
        Languages.current = Languages.SPANISH
        runBlocking { withContext(Dispatchers.IO) { db.clearAllTables() } }
        vm = ReaderViewModel(app); compose.activity.viewModelStore.put("reader", vm)
        var result: Result<Long>? = null
        vm.importText("Fixture", "Cuando Osiel Cárdenas llegó a la casa, Cárdenas pidió ayuda. El hombre estaba en la casa.") { result = it }
        await { result != null }; vm.openBook(result!!.getOrThrow())
        await { vm.open.value?.let { !it.loading && it.chapter != null && it.entity.analyzedTokens > 0 } == true && vm.analysis.value.isEmpty() }
    }
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }
    private fun rows() = runBlocking { db.highlightDao().all().filter { it.bookId == vm.open.value!!.entity.id } }
    private fun create(): Highlight {
        var result: Result<Unit>? = null
        vm.linkEntity("Osiel Cárdenas", null, "Основная заметка") { result = it }
        await { result != null }; result!!.getOrThrow()
        return rows().first { it.chapterIndex == -1 }
    }
    private fun target(): WikiNoteTarget {
        val o = vm.open.value!!; val c = o.chapter!!
        val bi = c.blocks.indexOfFirst { it is ReaderBlock.Text }
        val b = c.blocks[bi] as ReaderBlock.Text
        val start = b.shown.lastIndexOf("Cárdenas")
        return WikiNoteTarget(o.entity.id, c.index, c.title, bi, start, start + 8, "Cárdenas", b.shown, "Osiel Cárdenas")
    }

    @Test fun quoteIsIdempotentPreservesManualNotesAndSurvivesAnotherViewModelAndDetach() {
        val owner = create(); val t = target()
        val localId = runBlocking { db.highlightDao().insert(Highlight(bookId = t.book, chapterIndex = t.chapter, blockIndex = t.block,
            startChar = t.start, endChar = t.end, color = 4, note = "Моё наблюдение", text = t.term, chapterTitle = t.chapterTitle)) }
        runBlocking { vm.saveEntityQuote(t, owner.id); vm.saveEntityQuote(t, owner.id) }
        val q = rows().single { it.chapterIndex >= 0 }
        assertEquals(localId, q.id); assertEquals("Моё наблюдение", q.note); assertEquals(4, q.color)
        assertEquals(t.sentence, q.contextQuote); assertEquals(owner.text, q.entityName)
        val another = ReaderViewModel(app); compose.activity.viewModelStore.put("second", another)
        another.openBook(t.book); await { another.open.value?.let { !it.loading && it.chapter != null } == true }
        val persisted = runBlocking { withContext(Dispatchers.IO) { db.highlightDao().all() } }
        assertEquals(q, persisted.first { it.id == localId })
        runBlocking { vm.detachEntityQuote(q.id) }
        val detached = rows().first { it.id == q.id }
        assertTrue(detached.entityName.isEmpty()); assertEquals(t.sentence, detached.contextQuote)
        assertEquals(2, BookKnowledge.entries(t.book, rows()).size)
        runBlocking { vm.attachExistingQuote(q.id, owner.id) }
        assertEquals(1, BookKnowledge.entries(t.book, rows()).size)
        // Layout/font values are deliberately absent from the saved source address.
        assertEquals(listOf(t.chapter, t.block, t.start, t.end), rows().first { it.id == q.id }.let { listOf(it.chapterIndex, it.blockIndex, it.startChar, it.endChar) })
    }

    @Test fun oldWikiNoteCanBePromotedAndSharedWikiSaveAddsAPassageWithoutDeletingSources() {
        val t = target()
        val article = WikiArticle("es", 42, "Osiel Cárdenas", "Определение", 321, 1234, false)
        runBlocking { vm.saveWikipediaNote(t, article, article.extract) }
        val local = rows().single()
        assertFalse(BookKnowledge.entries(t.book, rows()).single().shared)
        val id = runBlocking { vm.promoteKnowledgeNote(local.id) }
        val entries = BookKnowledge.entries(t.book, rows())
        assertEquals(id, entries.single().record.id); assertEquals(local.id, entries.single().quotes.single().id)
        val original = rows().first { it.id == local.id }
        assertEquals(local.note, original.note)
        runBlocking { vm.saveWikipediaNote(t, article, article.extract); vm.saveKnowledgeWikipedia(id, article, "Дополненное объяснение") }
        assertEquals(2, rows().size)
        assertTrue(rows().first { it.id == id }.note.contains("Дополненное объяснение"))
        runBlocking { vm.saveKnowledgeNote(id, "Новое личное объяснение") }
        assertEquals("Новое личное объяснение", rows().first { it.id == id }.note)
        runBlocking { db.highlightDao().delete(id) }
        assertEquals(original.id, BookKnowledge.entries(t.book, rows()).single().record.id)
    }

    @Test fun foreignDeletedAndClosedTargetsCannotBeSaved() {
        val owner = create(); val t = target()
        val other = runBlocking { db.bookDao().insert(BookEntity(title = "Other", author = "", fileName = "other.epub", chapterCount = 1)) }
        val foreignId = runBlocking { db.highlightDao().insert(owner.copy(id = 0, bookId = other)) }
        assertTrue(runCatching { runBlocking { vm.saveEntityQuote(t, foreignId) } }.isFailure)
        assertTrue(runCatching { runBlocking { vm.saveEntityQuote(t.copy(term = "Other"), owner.id) } }.isFailure)
        runBlocking { db.highlightDao().delete(owner.id) }
        assertTrue(runCatching { runBlocking { vm.saveEntityQuote(t, owner.id) } }.isFailure)
        assertTrue(rows().isEmpty())
        vm.closeBook()
        assertTrue(runCatching { runBlocking { vm.saveEntityQuote(t, owner.id) } }.isFailure)
    }

    @Test fun actualSheetSearchDetailExportAndSourceJumpUseSavedRoomEntries() {
        val owner = create(); val t = target()
        runBlocking { vm.saveEntityQuote(t, owner.id) }
        var jumped: Highlight? = null
        var exported = ""
        compose.setContent { LexiconTheme {
            val all by vm.highlights(t.book).collectAsState(initial = emptyList())
            BookKnowledgeSheet(vm, vm.open.value!!, all, {}, { jumped = it }, { exported = BookKnowledge.markdown("Книга", "", it) })
        } }
        compose.onNodeWithText("Найти в записях").performTextReplacement("Ничего")
        compose.onNodeWithText("Ничего не найдено").assertExists()
        compose.onNodeWithText("Найти в записях").performTextReplacement("Cárdenas")
        compose.onNodeWithText(owner.text).performClick()
        compose.onNodeWithText("Общая запись книги").assertExists()
        compose.onNodeWithText("Экспортировать запись").performScrollTo().performClick()
        assertTrue(exported.contains(t.sentence))
        compose.onNodeWithText("Перейти к месту в книге").performScrollTo().performClick()
        assertEquals(t.start, jumped!!.startChar); assertEquals(t.block, jumped!!.blockIndex)
        compose.onNodeWithText("Отвязать, сохранив фрагмент").performScrollTo().performClick()
        await { rows().any { it.chapterIndex >= 0 && it.entityName.isEmpty() } }
    }
}
