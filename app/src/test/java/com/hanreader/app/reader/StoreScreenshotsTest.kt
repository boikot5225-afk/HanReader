package com.hanreader.app.reader

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.Appearance
import com.hanreader.app.AppearanceStore
import com.hanreader.app.BookEntity
import com.hanreader.app.GlossMode
import com.hanreader.app.Highlight
import com.hanreader.app.LexiconTheme
import com.hanreader.app.LocalThemeSpec
import com.hanreader.app.ReaderTheme
import com.hanreader.app.ReadingMode
import com.hanreader.app.ToneColors
import com.hanreader.app.epub.EpubBook
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Store screenshots (1080x1920) of real reader screens on the demo book written for them
 * (store/demo-book.epub). Run on demand: HANREADER_STORE_SHOTS=1 gradle :app:testDebugUnitTest --tests '*StoreScreenshotsTest'
 * Output: build/screens/store-*.png; tools/build_store_shots.py adds the captions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val engine by lazy {
        fun lines(f: String) = File("src/main/assets/zh/$f").bufferedReader().readLines().asSequence()
        ZhEngine(
            ZhSegmenterTest.dict, 0, HskLevels.parse(lines("hsk.tsv")),
            ZhEngine.parseRussian(lines("ru.tsv")), ZhEngine.parseRussian(lines("ru_ai.tsv")),
            ZhEngine.parseNames(lines("names.tsv")), russianChars = ZhEngine.parseRussianChars(lines("ru_chars.tsv"))
        )
    }
    private val book by lazy { EpubBook(File("../store/demo-book.epub")) }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File(File("build/screens").apply { mkdirs() }, "store-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** One page of chapter [index] in the given look; [para] draws each paragraph. */
    @Composable
    private fun Page(a: Appearance, index: Int, gloss: Map<String, String>? = null, para: @Composable (ReaderBlock.Text, Int, IntRange?, RubyMetrics, RubyColors) -> Unit) {
        val chapter = ChapterBuilder.build(book, index, engine)
        val vm = ReaderViewModel(app)
        val spec = LocalThemeSpec.current
        val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, spec, a.hanFontFor(spec).family)
        val colors = rubyColors(a, spec)
        PagedChapter(
            vm = vm, book = OpenBook(BookEntity(id = 1, title = "蓝色的书", author = "", fileName = "x", chapterCount = 2, chapterIndex = index), book, chapter, loading = false),
            chapter = chapter, metrics = metrics, colors = colors, appearance = a, showPinyin = true,
            hidePinyinFor = null, showBanner = false, glossFor = gloss, banner = {},
            paragraph = { b, i, lines -> para(b, i, lines, metrics, colors) },
            bars = PaddingValues(top = 24.dp, bottom = 8.dp), speed = 200f, keysEnabled = false,
            onTapCenter = {}, onAnyTap = { false }, onProgress = {}, registerJump = {}, registerBlockJump = {},
            bookmarkedBlocks = emptySet()
        )
    }

    private fun on() = assumeTrue(System.getenv("HANREADER_STORE_SHOTS") != null)

    @Test
    fun pinyinPage() {
        on()
        val a = Appearance(theme = ReaderTheme.PAPER, mode = ReadingMode.PAGES, fontSize = 24)
        AppearanceStore.update { a }
        compose.setContent {
            LexiconTheme {
                Page(a, 0) { b, _, lines, m, c -> RubyParagraph(b, m, true, null, emptySet(), null, c, {}, lines = lines) }
            }
        }
        shot("1-pinyin")
    }

    @Test
    fun hintsDarkTones() {
        on()
        val a = Appearance(theme = ReaderTheme.NOIR, mode = ReadingMode.PAGES, fontSize = 24, tones = ToneColors.PINYIN,
            markUnknown = true, markRecommended = true, gloss = GlossMode.UNKNOWN)
        AppearanceStore.update { a }
        val known = KnownMatcher(engine.hsk.wordsUpTo(HskSystem.HSK3, 2).toHashSet().let { it + KnownExpansion.expand(it, engine.dictionary) }, engine)
        val chapter = ChapterBuilder.build(book, 0, engine)
        val words = chapter.blocks.flatMap { (it as? ReaderBlock.Text)?.tokens.orEmpty() }.filter { it.kind == TokenKind.HAN }.map { it.text }.toSet()
        val unknown = words.filterTo(HashSet()) { !known.isKnown(it) && !VocabularyStats.isName(it, engine) }
        val recommended = setOf("书店", "特别", "奇怪")
        val gloss = (unknown - recommended).mapNotNull { w -> engine.glossShort(w)?.let { w to it } }.toMap()
        compose.setContent {
            LexiconTheme {
                Page(a, 0, gloss) { b, _, lines, m, c ->
                    RubyParagraph(b, m, true, null, emptySet(), null, c, {}, lines = lines, glossFor = gloss,
                        unknownMarks = unknown - recommended, recommendedMarks = recommended)
                }
            }
        }
        shot("2-hints")
    }

    @Test
    fun highlightsAndReadAloud() {
        on()
        val a = Appearance(theme = ReaderTheme.CLASSIC, mode = ReadingMode.PAGES, fontSize = 24)
        AppearanceStore.update { a }
        val chapter = ChapterBuilder.build(book, 1, engine)
        val sentences = Sentences.of(chapter)
        val reading = sentences[2]
        compose.setContent {
            LexiconTheme {
                Page(a, 1) { b, i, lines, m, c ->
                    val hl = if (i == 1) Highlights.tokens(b, listOf(Highlight(1, 1, 1, 1, 0, b.text.length, 0, "первая фраза книги", b.text, ""))) else null
                    RubyParagraph(
                        b, m, true, null, emptySet(), null, c, {}, lines = lines,
                        marked = hl?.first.orEmpty(), noted = hl?.second.orEmpty(),
                        highlight = if (i == reading.block) Highlights.range(b, reading.start, reading.end) else null,
                        spoken = if (i == reading.block) b.tokens.indexOfFirst { it.text == "真的" }.takeIf { it >= 0 }?.let { it..it } else null
                    )
                }
            }
        }
        shot("3-highlights")
    }

    @Test
    fun statistics() {
        on()
        AppearanceStore.update { Appearance(theme = ReaderTheme.PAPER) }
        val days = ReadingStats.lastDays(20261106, 14)
        val minutes = listOf(12, 0, 25, 18, 30, 22, 9, 8, 41, 22, 15, 0, 27, 19)
        val words = listOf(4, 0, 7, 5, 9, 6, 2, 3, 12, 8, 5, 0, 10, 6)
        compose.setContent {
            LexiconTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.padding(top = 16.dp)) {}
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Сегодня", "19 мин", "3 820 знаков", Modifier.weight(1f))
                        StatTile("Серия", "12 дней", "рекорд 12", Modifier.weight(1f))
                        StatTile("Повторения", "35", "сегодня", Modifier.weight(1f))
                    }
                    ChartPanel("Чтение за 14 дней, минут", days, minutes, "Всего ${minutes.sum()} мин") { d, v -> "${ReadingStats.label(d)}: $v мин" }
                    ChartPanel("Новые слова в карточках", days, words, "В карточках 214 · выучено 87") { d, v -> "${ReadingStats.label(d)}: $v слов" }
                }
            }
        }
        shot("4-stats")
    }
}
