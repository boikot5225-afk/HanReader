package com.hanreader.app.es

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.reader.ChapterBuilder
import com.hanreader.app.reader.ContextMeanings
import com.hanreader.app.reader.ReaderBlock
import com.hanreader.app.reader.fadingGlosses
import com.hanreader.app.reader.glossOf
import com.hanreader.app.zh.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's own meaning of a word for one place, for one book and for every book. */
class ContextMeaningsTest {
    private val engine by lazy { EsEngineTest.load() }

    @Test
    fun placeWinsOverBookWhichWinsOverDictionary() {
        val chapter = ChapterBuilder.build(TxtBook("Vendían crack en la calle. El crack llegó de Miami. Un crack del fútbol.".toByteArray(), "x"), 0, engine)
        val bi = chapter.blocks.indexOfFirst { it is ReaderBlock.Text }
        val block = chapter.blocks[bi] as ReaderBlock.Text
        val cracks = block.tokens.filter { it.kind == TokenKind.HAN && it.text == "crack" }
        assertEquals(3, cracks.size)
        val book = 7L
        val own = mapOf(
            ContextMeanings.inBook(book, engine.lemma("crack")) to "крэк",
            ContextMeanings.here(book, chapter.index, bi, cracks[2].start) to "звезда",
            // Another book's meaning is not used here.
            ContextMeanings.inBook(8L, "crack") to "чужое"
        )
        val glossFor = HashMap<String, String>()
        engine.glossShort("crack")?.let { glossFor["crack"] = it }
        ContextMeanings.addTo(glossFor, chapter, ContextMeanings.herein(own, book, chapter.index), ContextMeanings.ofBook(own, book), engine, setOf("crack"))
        assertEquals(listOf("крэк", "крэк", "звезда"), cracks.map { glossOf(glossFor, it, bi) })
        // A meaning for one place is shown even when the hints over this word have faded.
        val kept = fadingGlosses(chapter, glossFor, repeats = 1)[bi].orEmpty()
        assertTrue(block.tokens.indexOf(cracks[2]) in kept)
    }

    @Test
    fun keysCannotBeWords() {
        assertTrue(ContextMeanings.here(1, 2, 3, 4).startsWith("@"))
        assertTrue(ContextMeanings.inBook(1, "crack").startsWith("@"))
        assertEquals(mapOf("crack" to "крэк"), ContextMeanings.ofBook(mapOf("@1|b|crack" to "крэк", "crack" to "везде", "@1|o|0:1:5" to "здесь"), 1))
        assertEquals(mapOf("1:5" to "здесь"), ContextMeanings.herein(mapOf("@1|o|0:1:5" to "здесь", "@1|o|10:1:5" to "другая глава"), 1, 0))
    }
}
