package com.hanreader.app.reader

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.LexiconDatabase
import com.hanreader.app.ReaderSave
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end on the JVM: import an EPUB, open it, read a segmented chapter, save a word
 * from the text into the cards with its sentence and source.
 * The sample text is written for this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderFlowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < end) { "Timed out" }
            Thread.sleep(20)
        }
    }

    private fun sampleBook(): File {
        val file = File(app.cacheDir, "sample.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""")
            put(
                "content.opf",
                """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>小明的一天</dc:title>
                <dc:creator>测试</dc:creator></metadata><manifest>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="b"/></spine></package>"""
            )
            put(
                "a.xhtml",
                """<html><body><h1>第一章　早上</h1>
                <p>小明每天早上七点起床。他喜欢在公园里跑步，然后回家吃早饭。</p>
                <p>今天天气很好，他终于决定去图书馆看书。图书馆离他家不远，走路只要十分钟。</p>
                <p>“你今天想看什么书？”图书馆的老师笑着问他。</p></body></html>"""
            )
            put("b.xhtml", "<html><body><h1>第二章</h1><p>晚上他给妈妈打电话。</p></body></html>")
        }
        return file
    }

    @Test
    fun importOpenSaveAndRender() {
        val vm = ReaderViewModel(app)
        var imported: Result<Long>? = null
        vm.importBook(Uri.fromFile(sampleBook())) { imported = it }
        waitFor { imported != null }
        val bookId = imported!!.getOrThrow()

        vm.openBook(bookId)
        waitFor(60_000) { vm.open.value?.chapter != null || vm.open.value?.error != null }
        assertEquals(null, vm.open.value!!.error)
        val chapter = vm.open.value!!.chapter!!
        assertEquals("小明的一天", vm.open.value!!.entity.title)
        val paragraph = chapter.blocks[2] as ReaderBlock.Text
        val words = paragraph.tokens.map { it.text }
        assertTrue("segmented: $words", "终于" in words && "图书馆" in words && "决定" in words)
        val zhongyu = paragraph.tokens.first { it.text == "终于" }
        assertEquals("zhōngyú", zhongyu.pinyin)

        val sentence = ChapterBuilder.sentence(paragraph.text, zhongyu.start, zhongyu.end)
        assertEquals("今天天气很好，他终于决定去图书馆看书。", sentence)

        var saved: Result<Boolean>? = null
        vm.saveWord(
            ReaderSave("终于", "zhōng yú", "at last; finally", sentence, bookId, "小明的一天", chapter.index, chapter.title)
        ) { saved = it }
        waitFor { saved != null }
        assertTrue(saved!!.getOrThrow())
        val dao = LexiconDatabase.get(app).cardDao()
        // What the reader highlights as saved comes from this flow.
        assertTrue(runBlocking { dao.observeByLanguage(STUDY_LANGUAGE).first() }.any { it.word == "终于" })
        val card = runBlocking { dao.allCards() }.single { it.word == "终于" }
        assertEquals("zh-CN", card.language)
        assertEquals(sentence, card.example)
        assertNotNull(runBlocking { dao.allExamples() }.firstOrNull { it.cardId == card.id && it.example == sentence })

        // Saving the same word again adds no duplicate card.
        saved = null
        vm.saveWord(
            ReaderSave("终于", "zhōng yú", "at last; finally", "他终于回来了。", bookId, "小明的一天", 0, "")
        ) { saved = it }
        waitFor { saved != null }
        assertEquals(false, saved!!.getOrThrow())
        assertEquals(1, runBlocking { dao.allCards() }.count { it.word == "终于" })
    }

    @Test
    fun levelMarksAndManualMarks() {
        val vm = ReaderViewModel(app)
        val dao = LexiconDatabase.get(app).knownWordDao()
        var r: Result<Int>? = null
        vm.setLevel(HskSystem.HSK3, 1) { r = it }
        waitFor(60_000) { r != null }
        assertTrue(r!!.getOrThrow() > 500)
        assertTrue(runBlocking { dao.all() }.any { it.word == "我" && it.source == "hsk3:1" })

        vm.setKnown("终于", true)
        waitFor { runBlocking { dao.all() }.any { it.word == "终于" } }

        // Clearing the level removes only the level marks.
        r = null
        vm.setLevel(HskSystem.HSK3, 0) { r = it }
        waitFor { r != null }
        assertEquals(listOf("终于"), runBlocking { dao.all() }.map { it.word })
        assertEquals(LevelMark(HskSystem.HSK3, 0), vm.levelMark.value)

        vm.setKnown("终于", false)
        waitFor { runBlocking { dao.all() }.isEmpty() }
    }
}
