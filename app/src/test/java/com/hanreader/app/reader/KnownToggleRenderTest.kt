package com.hanreader.app.reader

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanreader.app.AppearanceStore
import com.hanreader.app.LexiconTheme
import com.hanreader.app.ReaderTheme
import com.hanreader.app.WordFont
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The word sheet header with the «Знаю / Не знаю» switch beside a short and a long word: build/screens/known-toggle.png. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KnownToggleRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val engine by lazy {
        fun lines(f: String) = java.io.File("src/main/assets/zh/$f").bufferedReader().readLines().asSequence()
        ZhEngine(com.hanreader.app.zh.ZhSegmenterTest.dict, 0, russianChars = ZhEngine.parseRussianChars(lines("ru_chars.tsv")))
    }

    @Test
    fun header() {
        AppearanceStore.update { it.copy(theme = ReaderTheme.NOIR) }
        compose.setContent {
            LexiconTheme {
                Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface).padding(24.dp)) {
                    for ((word, reading, known) in listOf(Triple("那个", "nà gè", false), Triple("目不转睛", "mù bù zhuǎn jīng", true), Triple("胡须", "hú xū", false))) {
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(bottom = 16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(word, fontFamily = WordFont, fontSize = if (word.length > 6) 28.sp else 40.sp, fontWeight = FontWeight.SemiBold)
                                Text(reading, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.padding(top = 8.dp).widthIn(max = 190.dp), horizontalAlignment = Alignment.End) {
                                KnownToggle(known = known, onKnown = {})
                                val syl = reading.split(" ")
                                CharBreakdown(word.mapIndexed { k, c -> c.toString() to engine.charGloss(c.toString(), syl.getOrNull(k)) }, Modifier.padding(top = 10.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File(File("build/screens").apply { mkdirs() }, "known-toggle.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        AppearanceStore.update { com.hanreader.app.Appearance() }
    }
}
