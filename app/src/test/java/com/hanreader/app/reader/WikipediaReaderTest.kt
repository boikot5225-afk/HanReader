package com.hanreader.app.reader

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.*
import com.hanreader.app.wiki.*
import com.hanreader.app.zh.TokenKind
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
class WikipediaReaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    private val article = WikiArticle("es", 42, "Una investigación", "Una introducción.", 321, 1234, false)
    private val document = WikiDocument(article, listOf(WikiBlock("pista para resolver el caso."), WikiBlock("Historia", 2), WikiBlock("Texto completo después de la introducción.")))
    private val dictionary = object : LanguageModule {
        override val info = Languages.SPANISH
        override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText = { text ->
            PreparedText(text, text, Regex("\\p{L}+|\\s+|[^\\p{L}\\s]").findAll(text).map {
                RToken(it.value, "", if (it.value.any(Char::isLetter)) TokenKind.HAN else if (it.value.isBlank()) TokenKind.SPACE else TokenKind.PUNCT,
                    it.range.first, it.range.last + 1, true)
            }.toList())
        }
        override fun glossShort(word: String, reading: String?) = if (word == "pista") "дорожка" else null
        override fun cardTranslation(word: String, reading: String?) = glossShort(word, reading)
    }
    private class FakeSource(val document: WikiDocument) : WikipediaSource {
        var searches = 0; var fullCalls = 0; var saved = false; var failFull = false
        override suspend fun search(language: String, query: String): List<WikiHit> { searches++; return listOf(WikiHit(42, document.article.title, "Справка")) }
        override suspend fun article(language: String, id: Long) = document.article
        override suspend fun translation(article: WikiArticle, language: String): WikiArticle? = null
        override suspend fun fullArticle(article: WikiArticle): WikiDocument { fullCalls++; check(!failFull); return document }
        override suspend fun savedArticles() = if (saved) listOf(document.article) else emptyList()
        override suspend fun saveArticle(document: WikiDocument) { saved = true }
    }
    private fun await(condition: () -> Boolean) { compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() } }
    @Before fun setup() {
        Languages.current = Languages.SPANISH
        app.getSharedPreferences("wiki_reading_positions", 0).edit().clear().commit()
        java.io.File(app.filesDir, "wikipedia-reading-v1").deleteRecursively()
        runBlocking { withContext(Dispatchers.IO) { LexiconDatabase.get(app).clearAllTables() } }
    }
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }

    @Test fun actualRubyReaderLooksUpAWordSavesASourceExcerptAndKeepsItsRangeAfterFontChange() {
        val source = FakeSource(document)
        val target = WikiNoteTarget(7, 1, "Глава", 2, 0, 5, "pista", "Contexto de la novela.")
        var savedNote = ""
        compose.setContent { LexiconTheme { WikipediaReader(document, source, dictionary, setOf("pista"), {},
            onSaveExcerpt = { a, text -> savedNote = wikipediaNote(target, a, text) },
            meanings = mapOf("pista" to "улика", ContextMeanings.inBook(7, "pista") to "чужое значение")) } }
        compose.onNodeWithText("Сохранить офлайн").performClick(); await { source.saved }
        compose.onNodeWithTag("wiki-article-list").performScrollToIndex(1)
        compose.onNodeWithTag("wiki-block-0").performTouchInput { click(Offset(width * .22f, height * .15f)) }
        compose.onNodeWithText("pista", substring = false).assertExists()
        compose.onNodeWithText("улика", substring = false).assertExists()
        compose.onNodeWithText("А+").performClick()
        compose.onNodeWithText("Весь абзац").performClick()
        compose.onNodeWithText("В заметку книги").performClick()
        await { savedNote.isNotEmpty() }
        assertTrue(savedNote.contains(document.blocks[0].text)); assertTrue(savedNote.contains(target.sentence))
        assertTrue(savedNote.contains(article.revisionUrl)); assertTrue(savedNote.contains("CC BY-SA 4.0"))
        assertFalse(savedNote.contains("отредактировано")) // A verbatim article passage, not an edited intro.
        compose.waitForIdle()
        compose.onNodeWithText("← Назад").assertIsDisplayed()
        val root = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val picture = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(picture)); java.io.File("build/screens").mkdirs()
        java.io.File("build/screens/wiki-full-reader.png").outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; picture.recycle()
    }

    @Test fun fullArticleLoadsOnlyOnTapAndReturningKeepsTheEditedDefinition() {
        val source = FakeSource(document)
        val target = WikiNoteTarget(1, 0, "Chapter", 0, 0, 5, "pista", "Una pista.")
        var note = ""
        compose.setContent { LexiconTheme { WikipediaSheet(target, "es-ES", source,
            onSave = { _, text -> note = text }, onDismiss = {}, engine = dictionary) } }
        compose.onNodeWithText("Найти", substring = false).performClick()
        compose.onNodeWithText(article.title).performClick()
        compose.onNodeWithText("Описание для заметки").performTextReplacement("Моё определение")
        assertEquals(0, source.fullCalls)
        compose.onNodeWithText("Читать статью целиком").performScrollTo().performClick()
        await { source.fullCalls == 1 }
        compose.onNodeWithText("Сохранить офлайн").assertExists()
        compose.onNodeWithText("← Назад").performClick()
        compose.onNodeWithText("Моё определение").assertExists()
        compose.onNodeWithText("Сохранить в заметки").performScrollTo().performClick()
        await { note.isNotEmpty() }; assertEquals("Моё определение", note)
    }

    @Test fun savedArticleCanBeOpenedAndRemovedFromTheLibraryWithoutOpeningABook() {
        val vm = ReaderViewModel(app); compose.activity.viewModelStore.put("reader", vm)
        runBlocking { vm.wikipedia.saveArticle(document) }
        compose.setContent { LexiconTheme { WikipediaLibrary(vm) {} } }
        compose.onNodeWithText("Читать", substring = false).performClick()
        compose.onNodeWithText("Сохранена офлайн").assertExists()
        assertNull(vm.open.value)
        compose.onNodeWithText("← Назад").performClick()
        compose.onNodeWithText("Удалить с устройства").performClick()
        await { runBlocking { vm.wikipedia.savedArticles().isEmpty() } }
        assertNull(vm.open.value)
    }

    @Test fun articlePositionAndFontSurviveReopeningAndSavingDoesNotMoveTheOriginalBook() {
        val vm = ReaderViewModel(app); compose.activity.viewModelStore.put("reader", vm)
        var imported: Result<Long>? = null
        vm.importText("Исходная книга", "Una pista estaba en la casa. El hombre buscaba la verdad en la ciudad.") { imported = it }
        await { imported != null }; vm.openBook(imported!!.getOrThrow())
        await { vm.open.value?.let { !it.loading && it.chapter != null } == true }
        val original = vm.open.value!!; val chapter = original.chapter!!
        val bi = chapter.blocks.indexOfFirst { it is ReaderBlock.Text && it.text.contains("pista") }
        val block = chapter.blocks[bi] as ReaderBlock.Text; val start = block.shown.indexOf("pista")
        val target = WikiNoteTarget(original.entity.id, chapter.index, chapter.title, bi, start, start + 5, "pista", block.shown)
        val long = WikiDocument(article, List(25) { WikiBlock("Fragmento $it para resolver el caso y encontrar una pista.") })
        val source = FakeSource(long)
        var showing by mutableStateOf(true)
        compose.setContent { LexiconTheme { if (showing) WikipediaReader(long, source, dictionary, emptySet(), { showing = false },
            onSaveExcerpt = { a, text -> vm.saveWikipediaNote(target, a, text) }) } }
        compose.onNodeWithTag("wiki-article-list").performScrollToIndex(8)
        compose.onNodeWithTag("wiki-block-7").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("В заметку книги").performClick()
        await { runBlocking { LexiconDatabase.get(app).highlightDao().all().any { it.bookId == original.entity.id && it.note.contains(long.blocks[7].text) } } }
        compose.onNodeWithText("А+").performClick()
        compose.onNodeWithText("← Назад").performClick()
        compose.runOnIdle { assertFalse(showing) }
        val after = vm.open.value!!
        assertEquals(original.entity.id, after.entity.id)
        assertEquals(listOf(original.entity.chapterIndex, original.entity.blockIndex, original.entity.charOffset), listOf(after.entity.chapterIndex, after.entity.blockIndex, after.entity.charOffset))
        assertEquals(8, app.getSharedPreferences("wiki_reading_positions", 0).getInt(long.key, -1))
        compose.runOnIdle { showing = true }
        compose.onNodeWithTag("wiki-block-7").assertIsDisplayed()
    }
}
