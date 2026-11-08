package com.hanreader.app.es

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.Appearance
import com.hanreader.app.AppearanceStore
import com.hanreader.app.BookEntity
import com.hanreader.app.GlossMode
import com.hanreader.app.LexiconTheme
import com.hanreader.app.LocalThemeSpec
import com.hanreader.app.ReaderTheme
import com.hanreader.app.ReadingMode
import com.hanreader.app.epub.BookFiles
import com.hanreader.app.reader.ChapterBuilder
import com.hanreader.app.reader.KnownMatcher
import com.hanreader.app.reader.OpenBook
import com.hanreader.app.reader.PagedChapter
import com.hanreader.app.reader.ReaderBlock
import com.hanreader.app.reader.ReaderViewModel
import com.hanreader.app.reader.RubyParagraph
import com.hanreader.app.reader.rememberRubyMetrics
import com.hanreader.app.reader.rubyColors
import com.hanreader.app.zh.TokenKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pages of Spanish text drawn by the reader, to look at the layout of a language with spaces.
 * By default a sample text written for the test (test resources es/muestra.txt); with a book of
 * your own (not committed): HANREADER_ES_BOOK=/path/book.epub. Output: build/screens/es-*.png
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpanishScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val engine by lazy { EsEngineTest.load() }
    private val path = System.getenv("HANREADER_ES_BOOK")
    private val book by lazy {
        if (path != null) BookFiles.open(File(path))
        else com.hanreader.app.epub.TxtBook(File("src/test/resources/es/muestra.txt").readBytes(), "muestra")
    }

    /** The first chapter with real body text. */
    private val index by lazy {
        book.chapters.indices.firstOrNull { i -> book.blocks(i).sumOf { (it as? com.hanreader.app.epub.EpubBlock.Text)?.text?.length ?: 0 } > 3000 } ?: 0
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File(File("build/screens").apply { mkdirs() }, "es-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Page(a: Appearance, gloss: Map<String, String>?, para: @Composable (ReaderBlock.Text, IntRange?, com.hanreader.app.reader.RubyMetrics, com.hanreader.app.reader.RubyColors) -> Unit) {
        val chapter = ChapterBuilder.build(book, index, engine)
        val vm = ReaderViewModel(app)
        val spec = LocalThemeSpec.current
        val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, spec, a.hanFontFor(spec).family)
        val colors = rubyColors(a, spec)
        PagedChapter(
            vm = vm, book = OpenBook(BookEntity(id = 1, title = "El narco", author = "", fileName = "x", chapterCount = book.chapters.size, chapterIndex = index), book, chapter, loading = false),
            chapter = chapter, metrics = metrics, colors = colors, appearance = a, showPinyin = false,
            hidePinyinFor = null, showBanner = false, glossFor = gloss, banner = {},
            paragraph = { b, _, lines -> para(b, lines, metrics, colors) },
            bars = PaddingValues(top = 24.dp, bottom = 8.dp), speed = 200f, keysEnabled = false,
            onTapCenter = {}, onAnyTap = { false }, onProgress = {}, registerJump = {}, registerBlockJump = {},
            bookmarkedBlocks = emptySet()
        )
    }

    private fun on() = Unit

    @Test
    fun plainPage() {
        on()
        val a = Appearance(theme = ReaderTheme.PAPER, mode = ReadingMode.PAGES, fontSize = 20)
        AppearanceStore.update { a }
        compose.setContent {
            LexiconTheme { Page(a, null) { b, lines, m, c -> RubyParagraph(b, m, false, null, emptySet(), null, c, {}, lines = lines) } }
        }
        shot("1-plain")
    }

    @Test
    fun hintsOverUnknownWords() {
        on()
        val a = Appearance(theme = ReaderTheme.NOIR, mode = ReadingMode.PAGES, fontSize = 20, markUnknown = true, gloss = GlossMode.UNKNOWN)
        AppearanceStore.update { a }
        // A beginner: only the 40 most common words of this chapter are known.
        val chapter = ChapterBuilder.build(book, index, engine)
        val words = chapter.blocks.flatMap { (it as? ReaderBlock.Text)?.tokens.orEmpty() }.filter { it.kind == TokenKind.HAN }
        val common = words.groupingBy { engine.lemma(it.text) }.eachCount().entries.sortedByDescending { it.value }.take(40).map { it.key }.toSet()
        val known = KnownMatcher(common, engine)
        val unknown = words.map { it.text }.filterTo(HashSet()) { !known.isKnown(it) }
        val gloss = unknown.mapNotNull { w -> engine.glossShort(w)?.let { w to it } }.toMap()
        compose.setContent {
            LexiconTheme {
                Page(a, gloss) { b, lines, m, c ->
                    RubyParagraph(b, m, false, null, emptySet(), null, c, {}, lines = lines, glossFor = gloss, unknownMarks = unknown)
                }
            }
        }
        shot("2-hints")
    }
}
