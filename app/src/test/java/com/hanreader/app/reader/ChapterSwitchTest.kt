package com.hanreader.app.reader

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.AppearanceStore
import com.hanreader.app.LexiconTheme
import com.hanreader.app.ReadingMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The reader on screen in page mode while the chapter changes: a chapter that is only an image,
 * then a chapter of text, then back. Own sample book.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChapterSwitchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun book(): File {
        val file = File(app.cacheDir, "switch.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="c.opf"/></rootfiles></container>""".toByteArray())
            put("c.opf", """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>换章</dc:title></metadata><manifest>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                <item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/></spine></package>""".toByteArray())
            put("a.xhtml", """<html><body><p><img src="i.png"/></p></body></html>""".toByteArray())
            put("i.png", ByteArray(16))
            val text = (1..40).joinToString("") { "<p>那天晚上一直在下雨，他站在门口，等一个不会来的人。第${it}段。</p>" }
            put("b.xhtml", "<html><body><h1>第二章</h1>$text</body></html>".toByteArray())
            put("c.xhtml", "<html><body><p>天亮了。</p><p><img src=\"i.png\"/></p></body></html>".toByteArray())
        }
        return file
    }

    /** The page view itself, with chapters swapped under it as the reader does while turning. */
    @Test
    fun pageViewSurvivesChapterSwap() {
        val engine = ZhEngine(com.hanreader.app.zh.ZhSegmenterTest.dict, 0)
        val epub = com.hanreader.app.epub.EpubBook(book())
        val chapters = epub.chapters.indices.map { ChapterBuilder.build(epub, it, engine) }
        val entity = com.hanreader.app.BookEntity(id = 1, title = "换章", author = "", fileName = "x", chapterCount = 3)
        val vm = ReaderViewModel(app)
        var shown by androidx.compose.runtime.mutableStateOf(chapters[0])
        compose.setContent {
            LexiconTheme {
                val a = com.hanreader.app.Appearance(mode = ReadingMode.PAGES)
                val spec = com.hanreader.app.LocalThemeSpec.current
                val metrics = rememberRubyMetrics(androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 0), a, spec, spec.defaultHanFont.family)
                val colors = rubyColors(a, spec)
                PagedChapter(
                    vm = vm, book = OpenBook(entity.copy(chapterIndex = shown.index), epub, shown, loading = false),
                    chapter = shown, metrics = metrics, colors = colors, appearance = a, showPinyin = true,
                    hidePinyinFor = null, showBanner = false, glossFor = null, banner = {},
                    paragraph = { b, i, lines -> RubyParagraph(b, metrics, true, null, emptySet(), null, colors, {}, lines = lines) },
                    bars = androidx.compose.foundation.layout.PaddingValues(), speed = 200f, keysEnabled = false,
                    onTapCenter = {}, onAnyTap = { false }, onProgress = {}, registerJump = {}, registerBlockJump = {},
                    bookmarkedBlocks = emptySet()
                )
            }
        }
        compose.waitForIdle()
        for (target in listOf(1, 0, 2, 1, 2, 0)) {
            shown = chapters[target]
            compose.waitForIdle()
        }
        epub.close()
    }

    /** The menu opens from the running header or footer (any x) and by a vertical swipe; the page does not turn. */
    @Test
    fun menuFromEdgesAndSwipe() {
        val engine = ZhEngine(com.hanreader.app.zh.ZhSegmenterTest.dict, 0)
        val epub = com.hanreader.app.epub.EpubBook(book())
        val chapter = ChapterBuilder.build(epub, 1, engine)
        val entity = com.hanreader.app.BookEntity(id = 1, title = "换章", author = "", fileName = "x", chapterCount = 3, chapterIndex = 1)
        val vm = ReaderViewModel(app)
        var menu = 0
        var page = -1
        compose.setContent {
            LexiconTheme {
                val a = com.hanreader.app.Appearance(mode = ReadingMode.PAGES)
                val spec = com.hanreader.app.LocalThemeSpec.current
                val metrics = rememberRubyMetrics(androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 0), a, spec, spec.defaultHanFont.family)
                val colors = rubyColors(a, spec)
                PagedChapter(
                    vm = vm, book = OpenBook(entity, epub, chapter, loading = false),
                    chapter = chapter, metrics = metrics, colors = colors, appearance = a, showPinyin = true,
                    hidePinyinFor = null, showBanner = false, glossFor = null, banner = {},
                    paragraph = { b, i, lines -> RubyParagraph(b, metrics, true, null, emptySet(), null, colors, {}, lines = lines) },
                    bars = androidx.compose.foundation.layout.PaddingValues(), speed = 200f, keysEnabled = false,
                    onTapCenter = { menu++ }, onAnyTap = { false }, onProgress = { page = it.position }, registerJump = {}, registerBlockJump = {},
                    bookmarkedBlocks = emptySet()
                )
            }
        }
        compose.waitForIdle()
        val root = compose.onRoot()
        // Left end of the running header: used to turn back a page, now the menu.
        root.performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.1f, 20f)) }
        compose.waitForIdle()
        assertEquals(1, menu)
        // Right end of the footer.
        root.performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.9f, height - 15f)) }
        compose.waitForIdle()
        assertEquals(2, menu)
        // A swipe up across the text.
        root.performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.3f) }
        compose.waitForIdle()
        assertEquals(3, menu)
        assertEquals("no page turned", 0, page)
        epub.close()
    }
}
