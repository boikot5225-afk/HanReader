package com.hanreader.app.es

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.reader.ChapterBuilder
import com.hanreader.app.reader.ChapterSummary
import com.hanreader.app.reader.ReaderBlock
import com.hanreader.app.zh.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The end-of-chapter review: words and phrases that had a hint, the tapped and the faded ones first. */
class ChapterSummaryTest {
    private val engine by lazy { EsEngineTest.load() }

    private val text = "El sicario llegó tarde. Otro sicario esperaba. El tercer sicario no vino. Un sicario más. " +
        "La casa estaba lejos. Los informes daban cuenta de fiestas. El perro dormía."

    @Test
    fun hintedWordsAndPhrasesComeBack() {
        val chapter = ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine)
        // Hints over the words not known yet: sicario, perro, the phrase dar cuenta de.
        val unknown = setOf("sicario", "perro", "lejos")
        val glossFor = HashMap<String, String>()
        chapter.blocks.filterIsInstance<ReaderBlock.Text>().forEach { b ->
            b.tokens.filter { it.kind == TokenKind.HAN }.forEach { t ->
                if (engine.lemma(t.text) in unknown) glossFor[t.text] = engine.glossShort(t.text) ?: "?"
                if (' ' in t.pinyin) glossFor[t.text + "|" + t.pinyin] = engine.glossShort(t.text, t.pinyin) ?: "?"
            }
        }
        val items = ChapterSummary.items(chapter, engine, glossFor, repeats = 3,
            isKnown = { _, key -> key == "lejos" }, skip = { _, _ -> false }, tapped = setOf("perro"))
        val keys = items.map { it.key }
        // Tapped first, then the word whose hint faded (four times, hints stop after three).
        assertEquals(listOf("perro", "sicario"), keys.filter { ' ' !in it })
        assertTrue(items.first { it.key == "sicario" }.faded)
        assertTrue(items.first { it.key == "perro" }.tapped)
        assertTrue("dar cuenta de" in keys)
        // A word marked known and words without a hint stay out.
        assertFalse("lejos" in keys)
        assertFalse("casa" in keys)
        assertEquals("daban cuenta de", items.first { it.key == "dar cuenta de" }.shown)
    }
}
