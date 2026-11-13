package com.hanreader.app.lang

import com.hanreader.app.es.EsEngineTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Meanings chosen by the words around (assets/es/context.tsv). Sentences are own examples. */
class ContextRulesTest {
    private val engine = EsEngineTest.load()

    private fun hint(text: String, word: String): String? {
        val t = engine.chapter(listOf(text), TextOptions())(text).tokens
        val tok = t.first { it.text == word }
        return engine.glossShort(tok.text, tok.pinyin.ifEmpty { null })
    }

    @Test
    fun conditionsOfTheRuleFile() {
        val r = ContextRules.parse(sequenceOf(
            "# a comment",
            "x\tnear:tren,vía\tA",
            "y\tafter:el ánimo\tB",
            "z\tbefore:mano de\tC",
            "w\tnext:a & after-verb\tD"
        ))
        fun s(vararg w: String, verbs: Set<Int> = emptySet()) = ContextRules.Sentence(w.toList(), { setOf(w[it]) }, { it in verbs })
        assertEquals("A", r.meaning(listOf("x"), s("x", "del", "tren"), 0))
        assertNull(r.meaning(listOf("x"), s("x", "del", "barco"), 0))
        assertEquals("B", r.meaning(listOf("y"), s("y", "su", "el", "ánimo"), 0))
        assertEquals("C", r.meaning(listOf("z"), s("echar", "mano", "de", "ciertos", "z"), 4))
        assertEquals("D", r.meaning(listOf("w"), s("v", "w", "a"), 1).let { null } ?: r.meaning(listOf("w"), s("v", "w", "a", verbs = setOf(0)), 1))
        assertNull(r.meaning(listOf("w"), s("v", "w", "de", verbs = setOf(0)), 1))
    }

    @Test
    fun spanishMeaningsByTheSentence() {
        assertEquals("шпала", hint("Los durmientes de la vía estaban podridos.", "durmientes"))
        assertEquals("спящий", hint("Los durmientes roncaban en la sala.", "durmientes").orEmpty().substringBefore(','))
        assertEquals("плачевно, жалко", hint("El coche se mueve lamentablemente.", "lamentablemente"))
        assertEquals("к сожалению", hint("Lamentablemente, no vino nadie.", "Lamentablemente").orEmpty().substringBefore(','))
        assertEquals("закалять (дух)", hint("Vaya templando su ánimo.", "templando"))
        assertEquals("уловка, средство", hint("Tuvo que echar mano de ciertos expedientes.", "expedientes"))
        assertEquals("общаться, обращаться (с кем-то)", hint("No trate a los vecinos.", "trate"))
        // The card puts the chosen meaning first.
        val text = "Los durmientes de la vía estaban podridos."
        val tok = engine.chapter(listOf(text), TextOptions())(text).tokens.first { it.text == "durmientes" }
        assertEquals("шпала", engine.cardTranslation(tok.text, tok.pinyin)!!.substringBefore(';'))
    }
}
