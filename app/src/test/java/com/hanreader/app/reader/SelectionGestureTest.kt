package com.hanreader.app.reader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tap opens a word; a long press and drag selects a passage. Own sample text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class SelectionGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val engine = ZhEngine(ZhSegmenterTest.dict, 0)
    private val text = "今天天气很好，他终于决定去图书馆看书。图书馆离他家不远，走路只要十分钟。"
    private val block = ReaderBlock.Text(text, false, engine.segmenter.segment(text).map {
        RToken(it.text, engine.pinyinOf(it), it.kind, it.start, it.end, it.entry?.hasDefinition == true)
    })

    @Test
    fun tapAndLongPressDrag() {
        val taps = ArrayList<Int>()
        val ranges = ArrayList<Triple<Int, Int, Boolean>>()
        compose.setContent {
            val measurer = rememberTextMeasurer(cacheSize = 0)
            val base = TextStyle(fontSize = 22.sp)
            val metrics = RubyMetrics(measurer, base, base.copy(fontSize = 11.sp), base.copy(fontSize = 27.sp))
            val colors = RubyColors(Color.Black, Color.Gray, Color.Blue, Color.LightGray, 3.dp, 8.dp)
            RubyParagraph(
                block, metrics, true, null, emptySet(), null, colors,
                onTap = { taps += it }, modifier = Modifier.width(360.dp).testTag("p"),
                onRange = { a, b, done -> ranges += Triple(a, b, done) }
            )
        }
        val node = compose.onNodeWithTag("p")
        // A tap near the start of the first line (after the indent): one word.
        node.performTouchInput { click(Offset(width * 0.25f, height * 0.12f)) }
        compose.waitForIdle()
        assertEquals(1, taps.size)
        assertTrue(ranges.isEmpty())

        // Hold on the first line, drag to the last line, lift.
        node.performTouchInput {
            down(Offset(width * 0.25f, height * 0.12f))
            advanceEventTime(900)
            moveTo(Offset(width * 0.5f, height * 0.55f))
            advanceEventTime(50)
            moveTo(Offset(width * 0.8f, height * 0.9f))
            advanceEventTime(50)
            up()
        }
        compose.waitForIdle()
        assertEquals("no tap after a long press", 1, taps.size)
        assertTrue(ranges.size >= 3)
        val (anchor, to, done) = ranges.last()
        assertTrue(done)
        assertEquals(ranges.first().first, anchor)
        assertTrue("selection spans lines: $anchor..$to", to - anchor >= 8)
    }
}
