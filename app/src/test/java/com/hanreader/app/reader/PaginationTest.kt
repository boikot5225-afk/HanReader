package com.hanreader.app.reader

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.hanreader.app.Appearance
import com.hanreader.app.ReaderTheme
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

/** Pages of a chapter built from sample text written for this test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaginationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val engine = ZhEngine(ZhSegmenterTest.dict, 0)
    private val sentences = listOf(
        "那天晚上一直在下雨。", "街灯很暗，他站在旧书店门口，等一个不会来的人。",
        "门开了，老板看了他一眼，问他今天还是不买书吗。", "他笑了笑，没有回答。",
        "雨水顺着他的帽子流下来，落在地上，像一封没写完的信。", "远处有一辆车慢慢开过去，车灯照亮了半条街。"
    )

    private fun block(text: String) = ReaderBlock.Text(text, false, engine.segmenter.segment(text).map {
        RToken(it.text, engine.pinyinOf(it), it.kind, it.start, it.end, it.entry?.hasDefinition == true)
    })

    private val chapter = ChapterContent(
        index = 0,
        title = "雨夜",
        blocks = (0 until 14).map { i -> block((0..(i % 4) + 2).joinToString("") { sentences[(i + it) % sentences.size] }) },
        parseMs = 0,
        chars = 0
    )

    @Test
    fun everyLineOnExactlyOnePageInOrder() {
        val a = Appearance()
        val spec = ReaderTheme.CLASSIC.spec
        var result by mutableStateOf<Pair<List<Page>, PageSpec>?>(null)
        var lineCounts by mutableStateOf<List<Int>?>(null)
        compose.setContent {
            val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, spec, spec.defaultHanFont.family)
            val colors = rubyColors(a, spec)
            val d = LocalDensity.current
            val pageSpec = remember {
                with(d) {
                    PageSpec(
                        widthPx = 340.dp.toPx(), heightPx = 520.dp.toPx(), paragraphSpacingPx = a.paragraphSpacing.dp.toPx(),
                        bannerHeightPx = 64.dp.toPx(), showBanner = true, gapPx = colors.wordGap.toPx(),
                        lineGapPx = colors.lineGap.toPx(), showPinyin = true, hidePinyinFor = null,
                        justify = colors.justify, indent = colors.indent
                    )
                }
            }
            LaunchedEffect(Unit) {
                result = Paginator.paginate(chapter, metrics, pageSpec) { null } to pageSpec
                lineCounts = chapter.blocks.map { b ->
                    val t = b as ReaderBlock.Text
                    layout(t.tokens, pageSpec.widthPx, metrics, false, true, null, pageSpec.gapPx, pageSpec.lineGapPx, pageSpec.justify, pageSpec.indent).lineCount
                }
            }
        }
        compose.waitUntil(20_000) { result != null && lineCounts != null }
        val (pages, pageSpec) = result!!
        assertTrue("several pages expected, got ${pages.size}", pages.size >= 3)
        assertEquals(PageItem.Banner, pages.first().items.first())

        // Lines of each block, in reading order, across all pages.
        val seen = chapter.blocks.indices.associateWith { mutableListOf<Int>() }
        var lastBlock = -1
        for (p in pages) for (item in p.items) if (item is PageItem.Lines) {
            assertTrue(item.block >= lastBlock)
            lastBlock = item.block
            seen.getValue(item.block).addAll(item.lines)
        }
        chapter.blocks.indices.forEach { b -> assertEquals("block $b", (0 until lineCounts!![b]).toList(), seen.getValue(b)) }

        // The saved position (a block) opens the page that shows it.
        val target = 9
        val page = pages[Paginator.pageFor(pages, target)]
        assertTrue(page.firstBlock <= target)
        assertTrue(page.items.any { it.block == target } || pages.getOrNull(Paginator.pageFor(pages, target) + 1)?.firstBlock?.let { it > target } != false)
        assertEquals(pages.lastIndex, Paginator.pageFor(pages, ReaderViewModel.END_OF_CHAPTER))
        assertTrue(pages.all { it.chars > 0 || it.items == listOf(PageItem.Banner) })
        assertTrue(pageSpec.heightPx > 0)
    }

    @Test
    fun twoPagesSideBySide() {
        val a = Appearance()
        val spec = ReaderTheme.NOIR.spec
        var pages by mutableStateOf<List<Page>?>(null)
        compose.setContent {
            val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), a, spec, spec.defaultHanFont.family)
            val colors = rubyColors(a, spec)
            val d = LocalDensity.current
            val pageSpec = remember {
                with(d) {
                    PageSpec(340.dp.toPx(), 420.dp.toPx(), a.paragraphSpacing.dp.toPx(), 0f, false, colors.wordGap.toPx(),
                        colors.lineGap.toPx(), true, null, colors.justify, colors.indent)
                }
            }
            LaunchedEffect(Unit) { pages = Paginator.paginate(chapter, metrics, pageSpec) { null } }
            val list = pages
            if (list != null) {
                Row(Modifier.background(Color.DarkGray).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    list.take(2).forEach { page ->
                        Column(Modifier.width(380.dp).height(460.dp).background(spec.background).border(1.dp, spec.border).padding(20.dp)) {
                            for (item in page.items) if (item is PageItem.Lines) {
                                if (item.spacingBefore) Spacer(Modifier.height(a.paragraphSpacing.dp))
                                RubyParagraph(chapter.blocks[item.block] as ReaderBlock.Text, metrics, true, null, emptySet(), null, colors, {}, lines = item.lines)
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(20_000) { pages != null }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File("build/screens").apply { mkdirs() }.resolve("pages.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
