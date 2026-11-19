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
class EntityLinksFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db get() = LexiconDatabase.get(app)
    private val previous = Languages.current
    private lateinit var vm: ReaderViewModel
    private fun await(condition: () -> Boolean) { compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() } }
    private fun mutate(work: ((Result<Unit>) -> Unit) -> Unit): Result<Unit> {
        var result: Result<Unit>? = null
        work { result = it }; await { result != null }; return result!!
    }
    @Before fun setup() {
        Languages.current = Languages.SPANISH
        runBlocking { withContext(Dispatchers.IO) { db.clearAllTables() } }
        vm = ReaderViewModel(app)
        compose.activity.viewModelStore.put("reader", vm)
        var imported: Result<Long>? = null
        vm.importText("Fixture", "Cuando Osiel Cárdenas llegó a la casa de Guzmán, Cárdenas pidió ayuda. El hombre estaba en la casa.") { imported = it }
        await { imported != null }; vm.openBook(imported!!.getOrThrow())
        await { vm.open.value?.let { !it.loading && it.chapter != null && it.entity.analyzedTokens > 0 } == true && vm.analysis.value.isEmpty() }
    }
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }
    private fun rows() = runBlocking { db.highlightDao().all().filter { it.bookId == vm.open.value!!.entity.id } }
    private fun create(name: String, note: String): Highlight {
        mutate { done -> vm.linkEntity(name, null, note, done) }.getOrThrow()
        return rows().first { it.text == name }
    }

    @Test fun linkingUsesOneNoteAndWikiSourceAndUndoKeepsBothOriginalRecords() {
        val full = create("Osiel Cárdenas", "Основная заметка")
        val short = create("Cárdenas", "Старая отдельная заметка")
        mutate { vm.linkEntity("Cárdenas", full.id, onDone = it) }.getOrThrow()
        // A note editor may still hold a snapshot from before the alias was added.
        vm.updateHighlight(full.copy(note = "Основная заметка обновлена"))
        await { rows().any { it.id == full.id && it.note == "Основная заметка обновлена" } }
        assertEquals(listOf("Cárdenas"), EntityLinks.aliases(rows().first { it.id == full.id }))
        assertEquals(full.id, EntityLinks.root(rows().first { it.id == short.id }, rows())!!.id)
        assertEquals("Старая отдельная заметка", rows().first { it.id == short.id }.note)
        val chapter = vm.open.value!!.chapter!!
        val bi = chapter.blocks.indexOfFirst { it is ReaderBlock.Text }
        val block = chapter.blocks[bi] as ReaderBlock.Text
        val start = block.text.lastIndexOf("Cárdenas")
        val target = WikiNoteTarget(full.bookId, chapter.index, chapter.title, bi, start, start + "Cárdenas".length,
            "Cárdenas", block.text, full.text)
        val article = WikiArticle("es", 42, "Osiel Cárdenas Guillén", "Описание из Википедии.", 321, 1234, false)
        runBlocking { vm.saveWikipediaNote(target, article, article.extract) }
        val linked = rows().first { it.id == full.id }
        assertTrue(linked.note.startsWith("Основная заметка")); assertTrue(linked.note.contains(article.revisionUrl))
        assertFalse(rows().first { it.id == short.id }.note.contains(article.revisionUrl))
        mutate { vm.unlinkEntity(full.id, "Cárdenas", it) }.getOrThrow()
        assertEquals(short.id, EntityLinks.root(rows().first { it.id == short.id }, rows())!!.id)
        assertEquals(listOf("Cárdenas"), EntityLinks.blocked(rows().first { it.id == full.id }))
        val id = full.bookId
        vm.closeBook(); vm.openBook(id)
        await { vm.open.value?.let { !it.loading && it.chapter != null } == true }
        assertEquals("Старая отдельная заметка", rows().first { it.id == short.id }.note)
        assertTrue(rows().first { it.id == full.id }.note.contains(article.revisionUrl))
    }
    @Test fun aVariantInheritedFromALinkedEntryCanAlsoBeDetached() {
        val full = create("Osiel Cárdenas", "Главная заметка")
        val short = create("Cárdenas", "Отдельная заметка")
        mutate { vm.linkEntity("El jefe", short.id, onDone = it) }.getOrThrow()
        mutate { vm.linkEntity("Cárdenas", full.id, onDone = it) }.getOrThrow()
        mutate { vm.linkEntity("El jefe", full.id, onDone = it) }.getOrThrow() // Already in this group.
        mutate { vm.unlinkEntity(full.id, "El jefe", it) }.getOrThrow()
        assertFalse(rows().any { EntityLinks.aliases(it).contains("El jefe") })
        assertEquals(full.id, EntityLinks.root(rows().first { it.id == short.id }, rows())!!.id)
        assertEquals("Отдельная заметка", rows().first { it.id == short.id }.note)
    }
    @Test fun duplicateAssignmentsAndLinksToAnotherBookAreRejectedWithoutChangingNotes() {
        val a = create("Osiel Cárdenas", "Основа")
        val b = create("Lázaro Cárdenas", "Другой человек")
        mutate { vm.linkEntity("Cárdenas", a.id, onDone = it) }.getOrThrow()
        assertTrue(mutate { vm.linkEntity("Cárdenas", b.id, onDone = it) }.isFailure)
        assertTrue(EntityLinks.aliases(rows().first { it.id == b.id }).isEmpty())
        val other = runBlocking { db.bookDao().insert(BookEntity(title = "Other", author = "", fileName = "other.epub", chapterCount = 1)) }
        val foreignId = runBlocking { db.highlightDao().insert(a.copy(id = 0, bookId = other, text = "Other person")) }
        assertTrue(mutate { vm.linkEntity("Alias", foreignId, onDone = it) }.isFailure)
        assertEquals("Основа", rows().first { it.id == a.id }.note)
        vm.closeBook()
        assertTrue(mutate { vm.linkEntity("Alias", a.id, onDone = it) }.isFailure)
    }
    @Test fun panelCanLinkAddAVariantAndUnlinkUsingTheActualRoomFlow() {
        val full = create("Osiel Cárdenas", "Общее описание")
        val block = ReaderBlock.Text("Cárdenas", false, listOf(RToken("Cárdenas", "", com.hanreader.app.zh.TokenKind.HAN, 0, 8, true)))
        compose.setContent { LexiconTheme {
            val all by vm.highlights(full.bookId).collectAsState(initial = emptyList())
            val wide = all.filter { it.chapterIndex == -1 }
            val match = EntityLinks.at(wide, block, 0, 8)
            EntityLinksPanel(wide, match?.entry, "Cárdenas", match?.automatic == true,
                onLink = { text, id, done -> vm.linkEntity(text, id, "", done) }, onUnlink = vm::unlinkEntity)
        } }
        compose.onNodeWithText("Это тот же человек или термин…").performClick()
        compose.onNodeWithText("Osiel Cárdenas", substring = false).performClick()
        await { rows().any { EntityLinks.aliases(it).contains("Cárdenas") } }
        compose.onNodeWithText("Общая запись: Osiel Cárdenas").assertExists()
        compose.onNodeWithText("Варианты имени и названия").performClick()
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/screens").mkdirs()
        java.io.File("build/screens/entity-links.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.onNodeWithText("Другой вариант имени или названия").performTextReplacement("Osiel Cárdenas Guillén")
        compose.onNodeWithText("Добавить вариант во всей книге").performClick()
        await { rows().any { EntityLinks.aliases(it).contains("Osiel Cárdenas Guillén") } }
        compose.onNodeWithText("Это другое — отменить связь").performClick()
        await { rows().any { EntityLinks.blocked(it).contains("Cárdenas") } }
        assertFalse(rows().any { EntityLinks.aliases(it).contains("Cárdenas") })
    }
}
