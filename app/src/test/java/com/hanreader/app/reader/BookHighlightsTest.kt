package com.hanreader.app.reader

import com.hanreader.app.Highlight
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A word highlighted in the whole book: marked wherever it stands, with one note. Own text, made-up names. */
class BookHighlightsTest {
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0)
    private fun block(text: String) =
        ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine).blocks.filterIsInstance<ReaderBlock.Text>().first()
    private fun wide(text: String, note: String = "") = Highlight(bookId = 1, chapterIndex = Highlights.BOOK_WIDE, blockIndex = 0,
        startChar = 0, endChar = 0, color = 2, note = note, text = text, chapterTitle = Highlights.BOOK_WIDE_TITLE)

    @Test
    fun anywhereInChineseText() {
        val b = block("邹晓岚走进来。我看见邹晓岚笑了。")
        val ranges = Highlights.occurrences(b, "邹晓岚")
        assertEquals(2, ranges.size)
        assertEquals("邹晓岚", b.text.substring(b.tokens[ranges[0].first].start, b.tokens[ranges[0].last].end))
    }

    @Test
    fun markedEverywhereWithItsNote() {
        val b = block("老张来了。后来老张又走了。")
        val dashed = Highlights.bookTokens(b, listOf(wide("老张", "邻居，退休工人")))
        assertTrue(dashed.isNotEmpty())
        assertEquals(setOf(Highlights.color(2)), dashed.values.toSet())
        // The card of either appearance finds the highlight.
        val second = b.text.lastIndexOf("老张")
        assertNotNull(Highlights.bookWideAt(listOf(wide("老张")), b, second, second + 2))
        assertNull(Highlights.bookWideAt(listOf(wide("老张")), b, b.text.indexOf("来"), b.text.indexOf("来") + 1))
        assertTrue(with(Highlights) { wide("x").wholeBook })
    }
}
