package com.hanreader.app.reader

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanreader.app.Appearance
import com.hanreader.app.HanFont
import com.hanreader.app.LocalHanFont
import com.hanreader.app.LocalThemeSpec
import com.hanreader.app.ReaderTheme
import com.hanreader.app.TextAlignMode
import com.hanreader.app.colorScheme
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the reader in every theme and every Chinese font to build/screens/. Own sample text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1680dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val engine = ZhEngine(ZhSegmenterTest.dict, 0)
    private val unknown = setOf("街灯", "暗", "顺着", "帽子", "落", "封")

    private fun block(text: String) = ReaderBlock.Text(text, false, engine.segmenter.segment(text).map {
        RToken(it.text, engine.pinyinOf(it), it.kind, it.start, it.end, it.entry?.hasDefinition == true)
    })

    private val paragraphs = listOf(
        block("那天晚上一直在下雨。街灯很暗，他站在旧书店门口，等一个不会来的人。"),
        block("他笑了笑，没有回答。雨水顺着他的帽子流下来，落在地上，像一封没写完的信。")
    )

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File("build/screens").apply { mkdirs() }.resolve(name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyTheme() {
        val a = Appearance(align = TextAlignMode.JUSTIFY)
        compose.setContent {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(ReaderTheme.NOIR, ReaderTheme.NEON, ReaderTheme.CLASSIC, ReaderTheme.PAPER).forEach { theme ->
                    val spec = theme.spec
                    val han = a.hanFontFor(spec).family
                    CompositionLocalProvider(LocalThemeSpec provides spec, LocalHanFont provides han) {
                        MaterialTheme(colorScheme = spec.colorScheme()) {
                            val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, spec, han)
                            val known = paragraphs.flatMap { p -> p.tokens.map { it.text } }.filter { it !in unknown }.toSet()
                            Column(
                                Modifier.width(400.dp).background(spec.background).padding(horizontal = a.margin.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(a.paragraphSpacing.dp)
                            ) {
                                Text(theme.title, color = spec.muted, fontFamily = spec.display, fontSize = 13.sp)
                                Text("雨夜", color = spec.text, fontFamily = han, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                                paragraphs.forEachIndexed { i, b ->
                                    val sel = if (i == 1) b.tokens.indexOfFirst { it.text == "顺着" } else -1
                                    RubyParagraph(
                                        b, metrics, showPinyin = true, hidePinyinFor = known, saved = setOf("雨水"),
                                        selection = if (sel >= 0) sel..sel else null, colors = rubyColors(a, spec), onTap = {}
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        save("themes.png")
    }

    @Test
    fun everyChineseFont() {
        compose.setContent {
            val spec = ReaderTheme.CLASSIC.spec
            Column(Modifier.width(900.dp).background(spec.background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HanFont.entries.forEach { f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(f.title, Modifier.width(180.dp), color = spec.muted, fontFamily = spec.body, fontSize = 18.sp)
                        Text("那天晚上一直在下雨，街灯很暗。", color = spec.text, fontFamily = f.family, fontSize = 30.sp)
                    }
                }
            }
        }
        save("fonts.png")
        assertTrue(File("build/screens/fonts.png").length() > 0)
    }
}
