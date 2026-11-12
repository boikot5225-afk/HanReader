package com.hanreader.app.es

import com.hanreader.app.Highlight
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.reader.ChapterBuilder
import com.hanreader.app.reader.Highlights
import com.hanreader.app.reader.ReaderBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A word highlighted in the whole book: marked wherever it stands, with one note. */
class BookHighlightsTest {
    private val engine by lazy { EsEngineTest.load() }
    private fun block(text: String) =
        ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine).blocks.filterIsInstance<ReaderBlock.Text>().first()
    private fun wide(text: String, note: String = "") = Highlight(bookId = 1, chapterIndex = Highlights.BOOK_WIDE, blockIndex = 0,
        startChar = 0, endChar = 0, color = 2, note = note, text = text, chapterTitle = Highlights.BOOK_WIDE_TITLE)

    @Test
    fun wholeWordsInAnyCase() {
        val b = block("Salinas habló con Salinasa. Después SALINAS se fue.")
        val found = Highlights.occurrences(b, "Salinas").map { r -> b.tokens[r.first].text }
        assertEquals(listOf("Salinas", "SALINAS"), found)
    }

    @Test
    fun phraseOfSeveralWords() {
        val b = block("El presidente Salinas de Gortari llegó. Salinas de Gortari habló.")
        val ranges = Highlights.occurrences(b, "Salinas de Gortari")
        assertEquals(2, ranges.size)
        assertEquals("Gortari", b.tokens[ranges[0].last].text)
    }

    @Test
    fun markedEverywhereWithItsNote() {
        val b = block("Arellano llegó. Luego Arellano se fue.")
        val dashed = Highlights.bookTokens(b, listOf(wide("Arellano", "jefe del cártel de Tijuana")))
        assertEquals(listOf("Arellano", "Arellano"), dashed.keys.sorted().map { b.tokens[it].text })
        assertEquals(setOf(Highlights.color(2)), dashed.values.toSet())
        // The card of either appearance finds the highlight.
        val second = b.tokens.indexOfLast { it.text == "Arellano" }
        assertNotNull(Highlights.bookWideAt(listOf(wide("Arellano")), b, b.tokens[second].start, b.tokens[second].end))
        assertNull(Highlights.bookWideAt(listOf(wide("Arellano")), b, 0, 0))
        assertTrue(with(Highlights) { wide("x").wholeBook })
    }
}
