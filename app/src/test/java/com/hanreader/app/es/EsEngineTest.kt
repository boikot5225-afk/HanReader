package com.hanreader.app.es

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.reader.ChapterBuilder
import com.hanreader.app.reader.KnownMatcher
import com.hanreader.app.reader.ReaderBlock
import com.hanreader.app.zh.TokenKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Spanish module on the real dictionary (assets/es). */
class EsEngineTest {
    private val engine = load()

    @Test
    fun formsLeadToTheirDictionaryWord() {
        val hablo = engine.analyses("habló").first()
        assertEquals("hablar", hablo.entry.lemma)
        assertTrue(hablo.grammar, hablo.grammar.contains("претерит"))
        assertEquals(setOf("ir", "ser"), engine.analyses("fue").map { it.entry.lemma }.toSet())
        assertEquals("casa", engine.lemma("Casas"))
        assertEquals("hablar", engine.lemma("hablo"))
    }

    @Test
    fun attachedPronounsAreTakenOff() {
        assertEquals("dar", engine.lemma("Dámelo"))
        assertEquals("decir", engine.lemma("dígamelo"))
        assertEquals("levantar", engine.lemma("levántate"))
    }

    @Test
    fun russianMeaningsFirst() {
        assertEquals("привет", engine.glossShort("Hola"))
        assertEquals("дом", engine.glossShort("casa"))
        assertNotNull(engine.cardTranslation("hablaban"))
        assertTrue(engine.cardTranslation("hablaban")!!.contains("говорить"))
    }

    @Test
    fun wordsSplitByLettersAndPunctuation() {
        val t = engine.tokenize("¿Qué tal, señor O'Donnell? Bien.")
        assertEquals(listOf("Qué", "tal", "señor", "O'Donnell", "Bien"), t.filter { it.kind == TokenKind.HAN }.map { it.text })
        assertEquals("¿", t.first().text)
        assertTrue(t.first { it.text == "Qué" }.defined)
    }

    @Test
    fun aKnownWordMakesItsFormsKnown() {
        val m = KnownMatcher(setOf("hablar", "y"), engine)
        assertTrue(m.isKnown("habló"))
        assertTrue(m.isKnown("y"))
        assertFalse(m.isKnown("comió"))
    }

    @Test
    fun chapterAndSearchForm() {
        val chapter = ChapterBuilder.build(TxtBook("Ella habló con su madre. Después comió.".toByteArray(), "x"), 0, engine)
        val block = chapter.blocks.filterIsInstance<ReaderBlock.Text>().first()
        assertEquals("habló", block.tokens.first { it.text.startsWith("habl") }.text)
        assertTrue(block.tokens.all { it.pinyin.isEmpty() })
        assertEquals("cancion", engine.searchForm("Canción"))
        assertEquals("Canción".length, engine.searchForm("Canción").length)
    }

    @Test
    fun namesAndBookCountsByDictionaryForm() {
        assertTrue(engine.isName("Blancornelas"))
        assertTrue(engine.isName("Sinaloa"))
        assertFalse(engine.isName("casa"))
        val book = TxtBook("Ella habló. Ellos hablaban. Hablar es fácil, dijo Blancornelas.".toByteArray(), "x")
        val words = engine.analyze(1L, book).words.associate { it.word to it.count }
        assertEquals(3, words["hablar"])
        assertEquals(1, words["Blancornelas"])
    }

    companion object {
        fun load(): EsEngine {
            val dir = File("src/main/assets/es")
            return EsEngine.load(File(dir, "dict.tsv").inputStream(), File(dir, "forms.tsv").inputStream(), File(dir, "grammar.tsv").inputStream())
        }
    }
}
