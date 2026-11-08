package com.hanreader.app.es

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EsGrammarTest {
    private val engine = EsEngineTest.load()

    private fun note(text: String, word: String): GrammarNote? {
        val t = engine.inContext(engine.tokenize(text))
        val i = t.indexOfFirst { it.text == word }
        val a = engine.analyses(t[i].text).let { all -> t[i].pinyin.takeIf { it.isNotEmpty() }?.let { r -> all.sortedBy { if (it.entry.lemma == r) 0 else 1 } } ?: all }
        return EsGrammar.note(engine, t, i, a)
    }

    @Test
    fun simpleTenses() {
        val n = note("Ayer llegó tarde.", "llegó")!!
        assertTrue(n.tense, n.tense.startsWith("Pretérito indefinido"))
        assertTrue(n.person!!, n.person!!.startsWith("3-е лицо ед."))
        assertTrue(note("Llovía sin parar.", "Llovía")!!.tense.startsWith("Pretérito imperfecto"))
        assertTrue(note("Quiero que vengas.", "vengas")!!.tense.startsWith("Presente de subjuntivo"))
        assertNull(note("La casa es grande.", "casa"))
    }

    @Test
    fun compoundForms() {
        val p = note("Si lo hubiera sabido, no habría venido.", "sabido")!!
        assertTrue(p.tense, p.tense.startsWith("Pretérito pluscuamperfecto de subjuntivo"))
        assertEquals("hubiera sabido", p.construction)
        assertTrue(note("Si lo hubiera sabido, no habría venido.", "habría")!!.tense.startsWith("Condicional compuesto"))
        assertTrue(note("Cuando llegué, ya se había ido.", "había")!!.tense.startsWith("Pretérito pluscuamperfecto"))
        assertTrue(note("Hoy he comido poco.", "comido")!!.tense.startsWith("Pretérito perfecto"))
        assertTrue(note("Estaba leyendo un libro.", "leyendo")!!.tense.startsWith("estar + gerundio"))
        assertTrue(note("Voy a comer ahora.", "Voy")!!.tense.startsWith("ir a + infinitivo"))
    }

    @Test
    fun attachedPronouns() {
        val n = note("Dámelo ahora.", "Dámelo")!!
        assertEquals("me lo", n.pronouns)
    }
}
