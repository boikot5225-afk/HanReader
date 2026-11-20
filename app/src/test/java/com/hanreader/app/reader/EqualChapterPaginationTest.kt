package com.hanreader.app.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.lang.Languages
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EqualChapterPaginationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val previous = Languages.current
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }

    @Test fun analysisReplacingAnEqualCoverChapterDoesNotLosePreparedPages() {
        Languages.current = Languages.CHINESE
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ReaderViewModel(app)
        compose.activity.viewModelStore.put("reader", vm)
        val source = TxtBook("测试。".toByteArray(), "Fixture")
        val chapter = ChapterContent(0, "Cover", listOf(ReaderBlock.Image("cover.jpg")), 0, 0)
        val entity = BookEntity(id = 7, title = "Fixture", author = "", fileName = "fixture.epub", chapterCount = 1)
        var book by mutableStateOf(OpenBook(entity, source, chapter, false))
        var ready = false
        var registrations = 0
        compose.setContent {
            LexiconTheme {
                val appearance = Appearance(mode = ReadingMode.PAGES)
                val theme = LocalThemeSpec.current
                val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), appearance, theme,
                    appearance.hanFontFor(theme).family)
                PagedChapter(vm, book, book.chapter!!, metrics, rubyColors(appearance, theme), appearance,
                    showPinyin = true, hidePinyinFor = null, showBanner = false, glossFor = null,
                    banner = {}, paragraph = { _, _, _ -> }, bars = PaddingValues(), speed = 200f,
                    keysEnabled = false, onTapCenter = {}, onAnyTap = { false }, onProgress = { ready = true },
                    registerJump = { if (it != null) registrations++ }, registerBlockJump = {}, bookmarkedBlocks = emptySet())
            }
        }
        compose.waitUntil(20_000) { ready }
        compose.onNodeWithText("1 / 1").assertExists()
        val originalRegistrations = registrations
        val replacement = chapter.copy()
        assertEquals(chapter, replacement)
        assertNotSame(chapter, replacement)
        // Background vocabulary analysis updates the entity and rebuilds an image-only
        // chapter into another equal data object, often with the same 0 ms build time.
        compose.runOnIdle { book = book.copy(entity = entity.copy(analyzedTokens = 123), chapter = replacement) }
        compose.waitForIdle()
        compose.onNodeWithText("1 / 1").assertExists()
        compose.runOnIdle { book = book.copy(chapter = replacement.copy(parseMs = replacement.parseMs + 17, title = "Updated title")) }
        compose.waitForIdle()
        compose.onNodeWithText("1 / 1").assertExists()
        assertEquals("Chapter metadata recreated the pager", originalRegistrations, registrations)
        source.close()
    }
    @Test fun volumeKeysTurnOnlyTheActiveReader() {
        Languages.current = Languages.SPANISH
        val source = TxtBook("Una pista.".toByteArray(), "Fixture")
        val text = (1..600).joinToString(" ") { "palabra$it" }
        val p = plainWikiText(text)
        val chapter = ChapterContent(0, "Article", listOf(ReaderBlock.Text(text, false, p.tokens)), 0, text.length)
        val entity = BookEntity(id = -42, title = "Article", author = "", fileName = "wiki", chapterCount = 1)
        val book = OpenBook(entity, source, chapter, false)
        var parentPage = -1; var articlePage = -1
        compose.setContent { LexiconTheme {
            val appearance = Appearance(mode = ReadingMode.PAGES)
            val theme = LocalThemeSpec.current
            val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), appearance, theme, WordFont)
            androidx.compose.foundation.layout.Box {
                for (active in listOf(false, true)) {
                    PagedChapter(null, book, chapter, metrics, rubyColors(appearance, theme), appearance,
                        showPinyin = false, hidePinyinFor = null, showBanner = false, glossFor = null,
                        banner = {}, paragraph = { _, _, _ -> }, bars = PaddingValues(), speed = 200f,
                        keysEnabled = active, onTapCenter = {}, onAnyTap = { false },
                        onProgress = { if (active) articlePage = it.position else parentPage = it.position },
                        registerJump = {}, registerBlockJump = {}, bookmarkedBlocks = emptySet())
                }
            }
        } }
        compose.waitUntil(20_000) { parentPage == 0 && articlePage == 0 }
        compose.runOnIdle { ReaderKeys.turns.tryEmit(1) }
        compose.waitUntil(20_000) { articlePage == 1 }
        assertEquals("A suspended book consumed the article's volume key", 0, parentPage)
        source.close()
    }

}
