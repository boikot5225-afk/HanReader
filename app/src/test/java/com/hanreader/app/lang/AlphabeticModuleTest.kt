package com.hanreader.app.lang

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.es.EsEngineTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared screens see Spanish only through [AlphabeticModule]: what a next language (English) must give. */
class AlphabeticModuleTest {
    private val engine: AlphabeticModule = EsEngineTest.load()

    private fun tokens(text: String) = engine.chapter(listOf(text), TextOptions())(text).tokens

    @Test
    fun cardOfAWord() {
        val text = "Ella habló con su madre."
        val t = tokens(text)
        val i = t.indexOfFirst { it.text == "habló" }
        val a = engine.cardAnalyses(t, text, i, i)
        assertEquals("hablar", a.first().entry.lemma)
        assertTrue(a.first().entry.isVerb)
        assertEquals("hablar", engine.cardLemma(a))
        assertTrue(engine.senses(a.first().entry).isNotEmpty())
        assertNotNull(engine.formNote(t, i, a))
    }

    @Test
    fun phraseAroundAWord() {
        val text = "Sin embargo, no vino."
        val t = tokens(text)
        val r = engine.phraseAt(t, text, 0)
        assertNotNull(r)
        assertEquals("sin embargo", engine.phraseKey(t, text, r!!.first, r.last) ?: text.substring(t[r.first].start, t[r.last].end).lowercase())
    }

    @Test
    fun conjugationIsATable() {
        val c = engine.conjugationTable("hablar")!!
        assertTrue(c.nonFinite.any { it.contains("hablando") })
        val present = c.tenses.first()
        assertTrue(present.second.any { (who, forms) -> who == "yo" && forms.contains("hablo") })
    }

    @Test
    fun bookLanguageFromItsCommonWords() {
        val es = TxtBook("La lluvia había empezado poco después de medianoche y no paraba. El barco llega a las ocho.".toByteArray(), "x")
        assertEquals(Languages.SPANISH, BookLanguages.recognize(es))
    }
}
