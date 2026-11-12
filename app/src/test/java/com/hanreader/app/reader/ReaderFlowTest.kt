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
        // HSK levels come from the app's Chinese data, which only the Chinese app packs.
        org.junit.Assume.assumeTrue(com.hanreader.app.lang.Languages.CHINESE in com.hanreader.app.lang.Languages.AVAILABLE)
        val vm = ReaderViewModel(app)
        val dao = LexiconDatabase.get(app).knownWordDao()
        var r: Result<Int>? = null
        vm.setLevel(HskSystem.HSK3, 1) { r = it }
        waitFor(60_000) { r != null }
        assertTrue(r!!.getOrThrow() > 500)

        // HSK 2.0 level 4 also counts HSK 3.0 levels 1-2: 事 is not in the old lists but obviously known.
        r = null
        vm.setLevel(HskSystem.HSK2, 4) { r = it }
        waitFor(60_000) { r != null }
        val words = runBlocking { dao.all() }.map { it.word }.toSet()
        assertTrue("事" in words && "说" in words && "他们" in words)
        r = null
        vm.setLevel(HskSystem.HSK3, 1) { r = it }
        waitFor(60_000) { r != null }
        assertTrue(runBlocking { dao.all() }.any { it.word == "我" && it.source == "hsk3:1" })

        vm.setKnown("终于", true)
        waitFor { runBlocking { dao.all() }.any { it.word == "终于" } }

        // Clearing the level removes only the level marks.
        r = null
        vm.setLevel(HskSystem.HSK3, 0) { r = it }
        waitFor { r != null }
        assertEquals(listOf("终于"), runBlocking { dao.all() }.map { it.word })
        assertEquals(LevelMark(HskSystem.HSK3, 0), vm.levelMark.value)

        // «Не знаю» is a mark of its own that a level never overrides; «Сбросить» removes it.
        vm.setKnown("终于", false)
        waitFor { runBlocking { dao.all() }.singleOrNull()?.source == ReaderViewModel.UNKNOWN }
        r = null
        vm.setLevel(HskSystem.HSK3, 4) { r = it }
        waitFor(60_000) { r != null }
        assertEquals(ReaderViewModel.UNKNOWN, runBlocking { dao.all() }.single { it.word == "终于" }.source)
        vm.clearKnown("终于")
        waitFor(60_000) { runBlocking { dao.all() }.firstOrNull { it.word == "终于" }?.source == "hsk3:4" } // 终于 is HSK 3.0 level 3
        r = null
        vm.setLevel(HskSystem.HSK3, 0) { r = it }
        waitFor { r != null }
        vm.clearKnown("终于")
        waitFor { runBlocking { dao.all() }.isEmpty() }
    }

    @Test
    fun analyzeBookAndAddChapterWords() {
        val vm = ReaderViewModel(app)
        var imported: Result<Long>? = null
        vm.importBook(Uri.fromFile(sampleBook())) { imported = it }
        waitFor { imported != null }
        val bookId = imported!!.getOrThrow()
        val dao = LexiconDatabase.get(app).bookDao()

        vm.analyzeBook(bookId)
        waitFor(60_000) { runBlocking { dao.get(bookId)!!.analyzedTokens } > 0 }
        val words = runBlocking { dao.observeWords(bookId).first() }
        assertTrue(words.any { it.word == "图书馆" && it.count == 3 && it.firstChapter == 0 })
        assertTrue(words.any { it.word == "打电话" && it.firstChapter == 1 })

        // Add a word met only in chapter 2 from the book-level list: its sentence comes from chapter 2.
        val book = runBlocking { dao.get(bookId)!! }
        val candidate = StudyCandidate("打电话", 1, "dǎ diàn huà", "to make a telephone call", 1)
        var added: Result<Int>? = null
        vm.addCandidates(book, listOf(candidate), null) { added = it }
        waitFor(60_000) { added != null }
        assertEquals(1, added!!.getOrThrow())
        val cardDao = LexiconDatabase.get(app).cardDao()
        val card = runBlocking { cardDao.allCards() }.single { it.word == "打电话" }
        assertEquals("晚上他给妈妈打电话。", card.example)
    }

    @Test
    fun bookmarksAndSearchInTheBook() {
        val vm = ReaderViewModel(app)
        var imported: Result<Long>? = null
        vm.importBook(Uri.fromFile(sampleBook())) { imported = it }
        waitFor { imported != null }
        val bookId = imported!!.getOrThrow()
        vm.openBook(bookId)
        waitFor(60_000) { vm.open.value?.chapter != null }
        val chapter = vm.open.value!!.chapter!!

        vm.addBookmark(chapter, 2)
        val dao = LexiconDatabase.get(app).bookmarkDao()
        waitFor { runBlocking { dao.observe(bookId).first() }.isNotEmpty() }
        val b = runBlocking { dao.observe(bookId).first() }.single()
        assertEquals(0, b.chapterIndex)
        assertEquals(2, b.blockIndex)
        assertTrue(b.snippet.startsWith("今天天气很好"))

        vm.search("图书馆")
        waitFor(60_000) { vm.search.value.query == "图书馆" && !vm.search.value.running }
        assertEquals(3, vm.search.value.hits.size)
        assertTrue(vm.search.value.hits.all { it.chapter == 0 })
        vm.search("dianhua")
        waitFor(60_000) { vm.search.value.query == "dianhua" && !vm.search.value.running }
        assertTrue(vm.search.value.hits.any { it.chapter == 1 })

        // Jump to a paragraph of another chapter: the position is saved there.
        vm.goToChapter(1, block = 1)
        waitFor(60_000) { vm.open.value?.chapter?.index == 1 && vm.open.value?.entity?.blockIndex == 1 }

        vm.removeBookmark(b.id)
        waitFor { runBlocking { dao.observe(bookId).first() }.isEmpty() }
    }

    @Test
    fun importsFb2AndTxtWithTitlesAndCovers() {
        val vm = ReaderViewModel(app)
        val txt = File(app.cacheDir, "雨夜的故事.txt").apply { writeText("第一章 雨夜\n那天晚上一直在下雨。\n第二章 早上\n天亮了。") }
        var r: Result<Long>? = null
        vm.importBook(Uri.fromFile(txt)) { r = it }
        waitFor { r != null }
        val dao = LexiconDatabase.get(app).bookDao()
        val txtBook = runBlocking { dao.get(r!!.getOrThrow())!! }
        assertEquals("雨夜的故事", txtBook.title)
        assertEquals(2, txtBook.chapterCount)
        assertTrue(txtBook.fileName.endsWith(".txt"))

        val fb2 = File(app.cacheDir, "download.bin").apply {
            writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?><FictionBook><description><title-info><book-title>灯</book-title></title-info></description>" +
                "<body><section><title><p>第一章</p></title><p>他站在门口。</p></section></body></FictionBook>")
        }
        r = null
        vm.importBook(Uri.fromFile(fb2)) { r = it }
        waitFor { r != null }
        val fb2Book = runBlocking { dao.get(r!!.getOrThrow())!! }
        assertEquals("灯", fb2Book.title)
        assertTrue(fb2Book.fileName.endsWith(".fb2"))
        vm.openBook(fb2Book.id)
        waitFor(60_000) { vm.open.value?.chapter != null }
        assertEquals("他站在门口。", (vm.open.value!!.chapter!!.blocks[1] as ReaderBlock.Text).text)

        // Not a book: refused, nothing left behind.
        val junk = File(app.cacheDir, "x.dat").apply { writeBytes(byteArrayOf(1, 0, 2, 0)) }
        r = null
        vm.importBook(Uri.fromFile(junk)) { r = it }
        waitFor { r != null }
        assertTrue(r!!.isFailure)
        assertTrue(File(app.filesDir, "books").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun markingANameRebuildsTheChapter() {
        val vm = ReaderViewModel(app)
        var imported: Result<Long>? = null
        vm.importBook(Uri.fromFile(sampleBook())) { imported = it }
        waitFor { imported != null }
        vm.openBook(imported!!.getOrThrow())
        waitFor(60_000) { vm.open.value?.chapter != null }
        val before = vm.open.value!!.chapter!!
        vm.setUserName("小明每", true) // a nonsense "name" across a word boundary: must become one token
        waitFor(60_000) {
            val c = vm.open.value?.chapter
            c != null && c !== before && (c.blocks[1] as ReaderBlock.Text).tokens.any { it.text == "小明每" }
        }
        assertTrue("小明每" in runBlocking { LexiconDatabase.get(app).userNameDao().all() }.map { it.word })
        vm.setUserName("小明每", false)
        waitFor(60_000) { (vm.open.value?.chapter?.blocks?.get(1) as? ReaderBlock.Text)?.tokens?.none { it.text == "小明每" } == true }
    }

    /** A file with no text (empty TXT): the import refuses it instead of storing a book that cannot open. */
    @Test
    fun emptyBookIsRefused() {
        val vm = ReaderViewModel(app)
        val file = File(app.cacheDir, "empty.txt").apply { writeText("  \n\n  ") }
        var imported: Result<Long>? = null
        vm.importBook(Uri.fromFile(file)) { imported = it }
        waitFor { imported != null }
        assertTrue("refused: $imported", imported!!.isFailure)
        assertTrue(imported!!.exceptionOrNull()!!.message!!.contains("текст"))
    }

    /** Pasted text becomes a book that opens; text without Chinese is refused. Own sample text. */
    @Test
    fun pastedTextIsABook() {
        val vm = ReaderViewModel(app)
        var r: Result<Long>? = null
        vm.importText("", "  雨夜\r\n\r\n\r\n\r\n那天晚上一直在下雨。   \n他站在门口，等一个不会来的人。\n", ) { r = it }
        waitFor { r != null }
        val id = r!!.getOrThrow()
        val book = runBlocking { LexiconDatabase.get(app).bookDao().get(id) }!!
        assertEquals("雨夜", book.title)
        vm.openBook(id)
        waitFor(60_000) { vm.open.value?.chapter != null || vm.open.value?.error != null }
        assertEquals(null, vm.open.value!!.error)
        val text = vm.open.value!!.chapter!!.blocks.filterIsInstance<ReaderBlock.Text>().joinToString("|") { it.text }
        assertTrue(text, text.contains("那天晚上一直在下雨。") && !text.contains("   "))

        var bad: Result<Long>? = null
        vm.importText("x", "Only English here.") { bad = it }
        waitFor { bad != null }
        assertTrue(bad!!.isFailure)
        assertTrue(bad!!.exceptionOrNull()!!.message!!.contains("китайск"))
    }

    /** «Поделиться» text and «Читать в HanReader» from the selection menu reach the paste window. */
    @Test
    fun sharedAndSelectedText() {
        com.hanreader.app.IncomingBooks.text.value = null
        com.hanreader.app.IncomingBooks.take(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, "他说了一句话。"))
        assertEquals("他说了一句话。", com.hanreader.app.IncomingBooks.text.value)
        com.hanreader.app.IncomingBooks.take(android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, "门开了。"))
        assertEquals("门开了。", com.hanreader.app.IncomingBooks.text.value)
        com.hanreader.app.IncomingBooks.text.value = null
        assertEquals(null, com.hanreader.app.IncomingBooks.pending.value)
    }
}
