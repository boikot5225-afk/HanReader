package com.hanreader.app.reader

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's own meaning of a word for one place, for one book and for every book. Own sample text. */
class ContextMeaningsTest {
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0)

    @Test
    fun placeWinsOverBookWhichWinsOverDictionary() {
        val chapter = ChapterBuilder.build(TxtBook("他打开了窗户。窗户外面很安静。她关上了窗户。".toByteArray(), "x"), 0, engine)
        val bi = chapter.blocks.indexOfFirst { it is ReaderBlock.Text }
        val block = chapter.blocks[bi] as ReaderBlock.Text
        val windows = block.tokens.filter { it.kind == TokenKind.HAN && it.text == "窗户" }
        assertEquals(3, windows.size)
        val book = 7L
        val own = mapOf(
            ContextMeanings.inBook(book, "窗户") to "окно (в книге)",
            ContextMeanings.here(book, chapter.index, bi, windows[2].start) to "ставня",
            // Another book's meaning is not used here.
            ContextMeanings.inBook(8L, "窗户") to "чужое"
        )
        val glossFor = HashMap<String, String>()
        glossFor["窗户"] = "окно"
        ContextMeanings.addTo(glossFor, chapter, ContextMeanings.herein(own, book, chapter.index), ContextMeanings.ofBook(own, book), setOf("窗户"))
        assertEquals(listOf("окно (в книге)", "окно (в книге)", "ставня"), windows.map { glossOf(glossFor, it, bi) })
        // A meaning for one place is shown even when the hints over this word have faded.
        val kept = fadingGlosses(chapter, glossFor, repeats = 1)[bi].orEmpty()
        assertTrue(block.tokens.indexOf(windows[2]) in kept)
    }

    @Test
    fun keysCannotBeWords() {
        assertTrue(ContextMeanings.isScoped(ContextMeanings.here(1, 2, 3, 4)))
        assertTrue(ContextMeanings.isScoped(ContextMeanings.inBook(1, "窗户")))
        assertEquals(mapOf("窗户" to "окно"), ContextMeanings.ofBook(mapOf("@1|b|窗户" to "окно", "窗户" to "везде", "@1|o|0:1:5" to "здесь"), 1))
        assertEquals(mapOf("1:5" to "здесь"), ContextMeanings.herein(mapOf("@1|o|0:1:5" to "здесь", "@1|o|10:1:5" to "другая глава"), 1, 0))
    }
}
