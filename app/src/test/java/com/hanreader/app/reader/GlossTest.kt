package com.hanreader.app.reader

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.hanreader.app.Appearance
import com.hanreader.app.ReaderTheme
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Translations above the pinyin; own sample text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlossTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val ru = File("src/main/assets/zh/ru.tsv").bufferedReader().useLines { ZhEngine.parseRussian(it) }
    private val ruAi = File("src/main/assets/zh/ru_ai.tsv").bufferedReader().useLines { ZhEngine.parseRussian(it) }
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0, russian = ru, russianAi = ruAi)
    private val text = "那天晚上一直在下雨。街灯很暗，他站在旧书店门口，等一个不会来的人。雨水顺着他的帽子流下来，落在地上。"
    private val block = ReaderBlock.Text(text, false, engine.segmenter.segment(text).map {
        RToken(it.text, engine.pinyinOf(it), it.kind, it.start, it.end, it.entry?.hasDefinition == true)
    })
    private val unknown = setOf("街灯", "暗", "旧书店", "顺着", "帽子", "落", "门口")

    @Test
    fun shortGlosses() {
        assertEquals("немедленно", engine.glossShort("立刻"))
        assertTrue(engine.glossShort("帽子")!!.isNotBlank())
        assertEquals(null, engine.glossShort("，"))
    }

    @Test
    fun glossesDoNotOverlapAndLinesBreakTheSame() {
        val a = Appearance()
        val spec = ReaderTheme.NOIR.spec
        var result by mutableStateOf<Pair<RubyLayout, RubyLayout>?>(null)
        compose.setContent {
            val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, spec, spec.defaultHanFont.family)
            val colors = rubyColors(a, spec)
            val d = LocalDensity.current
            LaunchedEffect(Unit) {
                val w = with(d) { 360.dp.toPx() }
                val gap = with(d) { colors.wordGap.toPx() }
                val lg = with(d) { colors.lineGap.toPx() }
                val glossFor = unknown.mapNotNull { u -> engine.glossShort(u)?.let { u to it } }.toMap()
                val plain = layout(block.tokens, w, metrics, false, true, null, gap, lg, colors.justify, colors.indent)
                val withGloss = layout(block.tokens, w, metrics, false, true, null, gap, lg, colors.justify, colors.indent, glossFor)
                result = plain to withGloss
            }
        }
        compose.waitUntil(20_000) { result != null }
        val (plain, gl) = result!!
        assertEquals(plain.lineCount, gl.lineCount)
        assertEquals(plain.items.map { it.token to it.x }, gl.items.map { it.token to it.x })
        assertTrue(gl.lineHeight > plain.lineHeight)
        assertTrue(gl.glosses.isNotEmpty())
        for ((_, line) in gl.glosses.groupBy { it.lineTop }) {
            val sorted = line.sortedBy { it.x }
            sorted.zipWithNext().forEach { (l, r) -> assertTrue("overlap", l.x + l.layout.size.width <= r.x + 1f) }
        }
    }

    @Test
    fun rendersTranslations() {
        val a = Appearance()
        compose.setContent {
            Column(Modifier.background(ReaderTheme.NOIR.spec.background), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(ReaderTheme.NOIR, ReaderTheme.CLASSIC).forEach { theme ->
                    val spec = theme.spec
                    val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, spec, spec.defaultHanFont.family)
                    val glossFor = unknown.mapNotNull { u -> engine.glossShort(u)?.let { u to it } }.toMap()
                    val known = block.tokens.filter { it.kind == TokenKind.HAN && it.text !in unknown }.map { it.text }.toSet()
                    Column(Modifier.width(400.dp).background(spec.background).padding(20.dp)) {
                        RubyParagraph(block, metrics, true, known, emptySet(), null, rubyColors(a, spec), {},
                            unknownMarks = unknown, glossFor = glossFor)
                    }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File("build/screens").apply { mkdirs() }.resolve("gloss.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
