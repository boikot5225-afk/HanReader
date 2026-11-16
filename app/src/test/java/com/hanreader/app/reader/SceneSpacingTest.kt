package com.hanreader.app.reader

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import com.hanreader.app.*
import com.hanreader.app.epub.EpubTextStyle
import com.hanreader.app.zh.TokenKind
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneSpacingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(word: String, style: EpubTextStyle = EpubTextStyle()) = ReaderBlock.Text(word, false,
        listOf(RToken(word, "", TokenKind.HAN, 0, word.length, true)), style = style)

    @Test fun semanticGapSurvivesZeroParagraphSpacingAndAdjacentMarginsDoNotDouble() {
        val previous = EpubTextStyle(spaceAfterEm = 2f)
        val next = EpubTextStyle(spaceBeforeEm = 3f)
        assertEquals(66f, blockSpacingPx(next, previous, 0f, 22f), 0f)
        assertEquals(80f, blockSpacingPx(next, previous, 14f, 22f), 0f)
        assertEquals(66f, blockSpacingPx(next, previous, 14f, 22f, atPageTop = true), 0f)
        assertEquals(66f, blockSpacingPx(next.copy(compactBefore = true), previous, 14f, 22f), 0f)
    }

    @Test fun paginatorIncludesTheSameSceneGapTheScrollViewUses() {
        var checked = false
        compose.setContent {
            val a = Appearance()
            val theme = ReaderTheme.CLASSIC.spec
            val m = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, theme, theme.defaultHanFont.family)
            LaunchedEffect(Unit) {
                val first = text("Antes", EpubTextStyle(spaceAfterEm = 2.4f))
                val second = text("Después")
                val chapter = ChapterContent(0, "Test", listOf(first, second), 0, 12)
                val spec = PageSpec(600f, 1800f, 14f, 0f, false, 0f, 4f, false, null, false, false)
                val pages = Paginator.paginate(chapter, m, spec) { null }
                val items = pages.single().items.filterIsInstance<PageItem.Lines>()
                assertEquals(0f, items[0].spacingBeforePx, 0f)
                val gap = blockSpacingPx(second.style, first.style, 14f, m.emWidth.toFloat())
                assertEquals(gap, items[1].spacingBeforePx, 0.01f)
                assertTrue(items[1].spacingBeforePx > 14f)
                assertEquals(1, items[1].block)
                assertEquals(0, pages.single().firstChar)
                checked = true
            }
        }
        compose.waitUntil(20_000) { checked }
    }

    @Test fun hugeStructuralGapInATinyViewportStillMakesProgressAndShowsEveryLine() {
        var checked = false
        compose.setContent {
            val a = Appearance()
            val theme = ReaderTheme.CLASSIC.spec
            val m = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, theme, theme.defaultHanFont.family)
            LaunchedEffect(Unit) {
                val blocks = listOf(text("Antes", EpubTextStyle(spaceBeforeEm = 8f, spaceAfterEm = 8f)),
                    text("Después", EpubTextStyle(spaceBeforeEm = 8f)))
                val chapter = ChapterContent(0, "Test", blocks, 0, 12)
                val spec = PageSpec(600f, 12f, 14f, 0f, false, 0f, 4f, false, null, false, false)
                val pages = Paginator.paginate(chapter, m, spec) { null }
                val items = pages.flatMap { it.items }.filterIsInstance<PageItem.Lines>()
                assertEquals(listOf(0, 1), items.map { it.block })
                assertEquals(2, items.sumOf { it.lines.count() })
                assertTrue(items.all { it.spacingBeforePx == 0f })
                checked = true
            }
        }
        compose.waitUntil(20_000) { checked }
    }
}
