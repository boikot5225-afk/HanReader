package com.hanreader.app.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.lang.Languages
import com.hanreader.app.zh.TokenKind
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A pending background hint update must not turn the translation row off or move the reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlossStabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val previous = Languages.current
    @Before fun setup() { Languages.current = Languages.SPANISH }
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }

    private fun block(): ReaderBlock.Text {
        val text = (1..10).joinToString(" ") { "La noche era tranquila y las luces de la ciudad brillaban en la ventana." }
        val tokens = Regex("\\p{L}+|\\s+|[^\\p{L}\\s]").findAll(text).map { RToken(it.value, "",
            if (it.value.first().isLetter()) TokenKind.HAN else TokenKind.SPACE,
            it.range.first, it.range.last + 1, true) }.toList()
        return ReaderBlock.Text(text, false, tokens)
    }

    @Test fun pendingTranslationsKeepTextAtTheSameHeightUntilTheUserTurnsThemOff() {
        val b = block()
        var checked = false
        compose.setContent {
            LexiconTheme {
                val a = Appearance(gloss = GlossMode.UNKNOWN)
                val theme = LocalThemeSpec.current
                val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, theme, a.hanFontFor(theme).family)
                LaunchedEffect(Unit) {
                    fun measure(mode: GlossMode, hints: Map<String, String>?) = layout(b.tokens, 340f, metrics,
                        false, false, null, 0f, 4f, true, false, glossesForMode(mode, hints))
                    val ready = measure(GlossMode.UNKNOWN, mapOf("noche" to "ночь"))
                    for (hints in listOf(null, emptyMap(), mapOf("noche" to "ночная пора ·"))) {
                        val updated = measure(GlossMode.UNKNOWN, hints)
                        assertEquals(ready.lineHeight, updated.lineHeight, 0f)
                        assertEquals(ready.height, updated.height, 0f)
                        assertEquals(ready.items.map { it.x to it.lineTop }, updated.items.map { it.x to it.lineTop })
                    }
                    assertTrue(measure(GlossMode.OFF, mapOf("noche" to "ночь")).lineHeight < ready.lineHeight)
                    checked = true
                }
            }
        }
        compose.waitUntil(20_000) { checked }
    }

    @Test fun pendingAndChangedHintsKeepTheSamePageAndDoNotRecreateThePager() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ReaderViewModel(app)
        compose.activity.viewModelStore.put("reader", vm)
        val paragraph = block()
        val chapter = ChapterContent(0, "Test", List(20) { paragraph }, 0, paragraph.text.length * 20)
        val source = TxtBook("Un texto para la prueba.".toByteArray(), "Test")
        val entity = BookEntity(id = 1, title = "Test", author = "", fileName = "test.txt", chapterCount = 1,
            blockIndex = 12, charOffset = 150)
        var hints by mutableStateOf<Map<String, String>?>(mapOf("noche" to "ночь"))
        var hiddenPinyin by mutableStateOf<Set<String>?>(null)
        val events = ArrayList<ReadingProgress>()
        var registrations = 0
        compose.setContent {
            LexiconTheme {
                val a = Appearance(gloss = GlossMode.UNKNOWN)
                val theme = LocalThemeSpec.current
                val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, theme, a.hanFontFor(theme).family)
                val colors = rubyColors(a, theme)
                PagedChapter(vm, OpenBook(entity, source, chapter, false), chapter, metrics, colors, a,
                    showPinyin = false, hidePinyinFor = hiddenPinyin, showBanner = false, glossFor = hints,
                    banner = {}, paragraph = { _, _, _ -> }, bars = PaddingValues(), speed = 200f, keysEnabled = false,
                    onTapCenter = {}, onAnyTap = { false }, onProgress = { events += it },
                    registerJump = { if (it != null) registrations++ }, registerBlockJump = {}, bookmarkedBlocks = emptySet())
            }
        }
        compose.waitUntil(20_000) { events.isNotEmpty() }
        val initial = events.last()
        assertTrue(initial.position > 0)
        val originalRegistrations = registrations
        for (update in listOf(null, emptyMap(), mapOf("noche" to "вечер ·"), null, mapOf("luces" to "огни"))) {
            compose.runOnIdle { hints = update; hiddenPinyin = setOf("noche", "luces") }
            compose.waitForIdle()
            assertTrue("hints changed page geometry: $events", events.all {
                it.position == initial.position && it.count == initial.count && it.firstBlock == initial.firstBlock
            })
            assertEquals("hint refresh recreated the pager", originalRegistrations, registrations)
        }
        source.close()
    }
}
