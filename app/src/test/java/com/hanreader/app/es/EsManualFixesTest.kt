package com.hanreader.app.es

import com.hanreader.app.lang.TextOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** data/es/manual.tsv is in the dictionary, and feminine and plural participles lead to their verb. */
class EsManualFixesTest {
    private val engine = EsEngineTest.load()

    @Test
    fun manualFileIsApplied() {
        val rows = File("../data/es/manual.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split("\t") }
        assertTrue(rows.isNotEmpty())
        for (p in rows) {
            val e = engine.entry(p[0].lowercase())
            assertTrue(p[0], e != null)
            if (p[2].isNotBlank()) assertEquals(p[0], p[2].split(" ; ").first(), e!!.ru.first())
            if (p.getOrElse(3) { "" }.isNotBlank()) assertEquals(p[0], p[3], e!!.short)
        }
    }

    @Test
    fun participlesLeadToTheirVerb() {
        // «indicaciones contenidas en las guías»: the participle of contener, not only the adjective.
        assertTrue(engine.analyses("contenidas").any { it.entry.lemma == "contener" && it.grammar.contains("причастие") })
        assertTrue(engine.analyses("abandonadas").any { it.entry.lemma == "abandonar" })
        // The adjective stays first: «una casa abandonada».
        assertEquals("abandonado", engine.analyses("abandonada").first().entry.lemma)
    }

    @Test
    fun accentedFormsAndNewWords() {
        // «El narco» (Grillo): ciar, criar, fiar, guiar, liar, piar — the generator wrote the forms
        // with a mandatory accent without it (crie, crio); the book had «me crié», «crió», «lió».
        for ((form, lemma) in listOf("crié" to "criar", "crió" to "criar", "guió" to "guiar",
                "fió" to "fiar", "lió" to "liar", "pió" to "piar", "ció" to "ciar"))
            assertTrue("$form -> $lemma", engine.analyses(form).any { it.entry.lemma == lemma && it.grammar.contains("претерит") })
        assertTrue(engine.analyses("puticlubes").any { it.entry.lemma == "puticlub" })
        assertTrue(engine.analyses("cactos").any { it.entry.lemma == "cacto" })
        assertEquals("текила", engine.entry("tequila")!!.short)
    }

    @Test
    fun falseFriendsAndPhrases() {
        assertEquals("не знать", engine.entry("ignorar")!!.short)
        val text = "Lo dejó a su suerte en la estación."
        val t = engine.chapter(listOf(text), TextOptions())(text).tokens
        val i = t.indexOfFirst { it.text == "su" }
        assertEquals("a su suerte", engine.phraseAt(t, text, i)?.let { r -> text.substring(t[r.first].start, t[r.last].end) })
    }
}
