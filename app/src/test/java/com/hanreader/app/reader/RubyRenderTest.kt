package com.hanreader.app.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanreader.app.LexiconTheme
import com.hanreader.app.WordFont
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders paragraphs with pinyin to build/screens/ruby.png for a visual check. Own sample text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RubyRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val engine = ZhEngine(ZhSegmenterTest.dict, 0)

    private fun block(text: String, heading: Boolean = false) = ReaderBlock.Text(
        text, heading,
        engine.segmenter.segment(text).map {
            RToken(it.text, engine.pinyinOf(it), it.kind, it.start, it.end, it.entry?.hasDefinition == true)
        }
    )

    @Test
    fun rendersPinyinAboveWords() {
        val blocks = listOf(
            block("第一章　早上", heading = true),
            block("小明每天早上七点起床。他喜欢在公园里跑步，然后回家吃早饭。"),
            block("今天天气很好，他终于决定去图书馆看书。图书馆离他家不远，走路只要十分钟。"),
            block("“你今天想看什么书？”图书馆的老师笑着问他。")
        )
        val sel = blocks[2].tokens.indexOfFirst { it.text == "终于" }
        compose.setContent {
            LexiconTheme {
                val scheme = MaterialTheme.colorScheme
                val measurer = rememberTextMeasurer(cacheSize = 0)
                val base = TextStyle(fontSize = 22.sp, fontFamily = WordFont, color = scheme.onBackground)
                val metrics = RubyMetrics(
                    measurer, base,
                    base.copy(fontSize = 11.sp, fontFamily = null, color = scheme.onSurfaceVariant),
                    base.copy(fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                )
                val colors = RubyColors(scheme.onBackground, scheme.onSurfaceVariant, scheme.primary,
                    scheme.secondaryContainer, 3.dp, 8.dp)
                Column(
                    Modifier.width(411.dp).background(scheme.background).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    blocks.forEachIndexed { i, b ->
                        RubyParagraph(
                            b, metrics, showPinyin = i != 3, saved = setOf("图书馆"),
                            selection = if (i == 2) sel..sel else null, colors = colors, onTap = {}
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // captureToImage() waits for a redraw Robolectric never signals; draw the view tree directly.
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        val out = File("build/screens").apply { mkdirs() }
        File(out, "ruby.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val heading = blocks[0].tokens.first { it.text.startsWith("第一") }
        check(heading.pinyin == heading.pinyin.lowercase()) { "composed pinyin must be lower-case: ${heading.pinyin}" }
        check(blocks.all { b -> b.tokens.none { it.kind == TokenKind.HAN && it.pinyin.isEmpty() } })
    }
}
