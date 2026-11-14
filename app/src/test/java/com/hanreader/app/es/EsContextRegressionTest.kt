package com.hanreader.app.es

import com.hanreader.app.lang.ContextRules
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.lang.SentenceBoundary
import org.junit.Assert.*
import org.junit.Test

/** Original synthetic examples only: no user book is required or copied into CI. */
class EsContextRegressionTest {
    private val engine = EsEngineTest.load()
    private fun card(text: String, word: String): Pair<String, String> {
        val tokens = engine.chapter(listOf(text), TextOptions())(text).tokens
        val i = tokens.indexOfFirst { it.text == word }
        assertTrue(word, i >= 0)
        val a = engine.cardAnalyses(tokens, text, i, i).first()
        return a.entry.lemma to engine.senses(a.entry).first()
    }
    @Test fun polysemyAndCacheAreOccurrenceSpecific() {
        repeat(3) {
            assertTrue(card("El capo controla la plaza del tráfico.", "plaza").second.contains("территория"))
            assertEquals("площадь", card("La plaza central tiene una fuente.", "plaza").second)
            assertEquals("площадь", engine.entry("plaza")!!.ru.first())
            assertNull(engine.entry("plaza")!!.contextMeaning)
        }
    }
    @Test fun phraseHeadKeepsItsContextInTheWordCard() {
        assertEquals("отмывание денег", card("La policía investiga el blanqueo de dinero.", "blanqueo").second)
        assertEquals("отбеливание", card("El blanqueo de la tela necesita cloro.", "blanqueo").second)
    }
    @Test fun musicAndCrimeRemainDistinct() {
        assertEquals("музыкальная группа", card("Una banda toca música.", "banda").second)
        assertEquals("банда", card("La banda del cártel huyó.", "banda").second)
    }
    @Test fun regionalSensesDoNotReplaceLiteralSenses() {
        assertTrue(card("El halcón informa al cártel.", "halcón").second.contains("осведомитель"))
        assertEquals("сокол", card("Un halcón vuela sobre el bosque.", "halcón").second)
        assertEquals("взятка", card("El policía pidió una mordida al conductor.", "mordida").second)
        assertEquals("укус", card("La mordida del perro dejó una marca.", "mordida").second)
        assertEquals("клетка", card("La célula del tejido se divide.", "célula").second)
        assertTrue(card("La célula de los Zetas huyó.", "célula").second.contains("ячейка"))
    }
    @Test fun contextSelectsLemmaAndPartOfSpeech() {
        assertEquals("mordida" to "взятка", card("Pagaba mordidas a la policía.", "mordidas"))
        assertEquals("corrido", card("El músico canta corridos mexicanos.", "corridos").first)
        assertTrue(card("El músico canta corridos mexicanos.", "corridos").second.contains("баллада"))
        assertEquals("baja", card("La guerra causó muchas bajas.", "bajas").first)
        assertEquals("bajo", card("Las casas bajas tienen patios.", "bajas").first)
    }
    @Test fun hintsCardAndSavedTranslationAgree() {
        val text = "Los durmientes de la vía estaban gastados."
        val tokens = engine.chapter(listOf(text), TextOptions())(text).tokens
        val i = tokens.indexOfFirst { it.text == "durmientes" }
        val t = tokens[i]
        assertEquals("шпала", engine.glossShort(t.text, t.pinyin))
        val a = engine.cardAnalyses(tokens, text, i, i).first()
        assertEquals("шпала", engine.senses(a.entry).first())
        assertEquals("шпала", engine.cardTranslation(t.text, t.pinyin)!!.substringBefore(';'))
    }
    @Test fun contextWinsOverRegionPreference() {
        val previous = com.hanreader.app.AppearanceStore.appearance.value
        try {
            com.hanreader.app.AppearanceStore.update { it.copy(esVariant = EsVariant.LATAM) }
            assertEquals("укус", card("La mordida del perro dejó una marca.", "mordida").second)
        } finally { com.hanreader.app.AppearanceStore.update { previous } }
    }
    @Test fun longPhrasesAndWhitespace() {
        for (text in listOf("De la noche a la mañana cambió.", "Cuernos\u00a0de  chivo son fusiles.")) {
            val tokens = engine.chapter(listOf(text), TextOptions())(text).tokens
            val i = tokens.indexOfFirst { it.text == "noche" || it.text == "chivo" }
            val range = engine.phraseAt(tokens, text, i)!!
            val key = engine.phraseKey(tokens, text, range.first, range.last)
            assertTrue(key, key in setOf("de la noche a la mañana", "cuerno de chivo"))
            assertNotNull(engine.cardAnalyses(tokens, text, range.first, range.last).firstOrNull())
        }
        val text = "Una banda. Sonora es otra palabra."
        assertNull(engine.phraseAt(engine.tokenize(text), text, 2))
    }
    @Test fun nominalPhraseFormsKeepTheCanonicalLemma() {
        val text = "Vigilaban las casas francas."
        val t = engine.chapter(listOf(text), TextOptions())(text).tokens
        val a = t.indexOfFirst { it.text == "casas" }; val b = t.indexOfFirst { it.text == "francas" }
        assertEquals("casa franca", engine.cardAnalyses(t, text, a, b).first().entry.lemma)
        assertEquals("конспиративная квартира", engine.glossShort(t[a].text, t[a].pinyin))
    }
    @Test fun unicodeDoesNotLoseAccentsOrOffsets() {
        val text = "El halco\u0301n informa al cártel."
        val t = engine.tokenize(text).first { it.text.startsWith("halco") }
        assertEquals("halcón", engine.lemma(t.text))
        assertEquals(t.text, text.substring(t.start, t.end))
        assertEquals("el", engine.lemma("el"))
        assertEquals("él", engine.lemma("él"))
    }
    @Test fun punctuationAndAbbreviationsBoundTheWindow() {
        assertFalse(SentenceBoundary.ends("El Sr. Pérez", 5))
        assertFalse(SentenceBoundary.ends("1.500 pesos", 1))
        assertEquals("сокол", card("El cártel huyó. Un halcón vuela.", "halcón").second)
        assertTrue(card("El Sr. Pérez contrató un halcón para el cártel.", "halcón").second.contains("осведомитель"))
    }
    @Test fun malformedRulesNeverBecomeBroaderRules() {
        for (line in listOf("x\tnear:tren & typo:barco\twrong", "x\twithin:100:tren\twrong")) {
            try { ContextRules.parse(sequenceOf(line)); fail("Invalid rule accepted") }
            catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
    }
    @Test fun russianAnalysisPrecedesEnglishOnlyFallback() {
        val dictionary = "alone\tсущ.\t\tEnglish only\tw\nforma\tсущ.\tформа\tform\tw\nformas\tсущ.\t\tEnglish only\tw\n"
        val e = EsEngine.load(dictionary.byteInputStream(), "formas\tforma:0\n".byteInputStream(), "мн. ч.\n".byteInputStream())
        assertEquals("форма", e.glossShort("formas"))
        assertTrue(e.senses(e.analyses("alone").first().entry).isEmpty())
        assertEquals("English only", e.cardTranslation("alone"))
        assertNull(e.cardTranslation("missingword"))
    }

    @Test fun godfatherTitleAndMafiaPhraseAreNotInventedBaptismEvidence() {
        assertTrue(card("El padrino del niño estuvo en el bautizo.", "padrino").second.contains("крёстный отец"))
        assertTrue(card("La película El padrino tuvo éxito.", "padrino").second.contains("крёстный отец"))
        val text = "El padrino mafioso dirigía el cártel."
        val tokens = engine.chapter(listOf(text), TextOptions())(text).tokens
        val a = tokens.indexOfFirst { it.text == "padrino" }; val b = tokens.indexOfFirst { it.text == "mafioso" }
        val article = engine.cardAnalyses(tokens, text, a, b).first().entry
        assertEquals("мафиозный босс", engine.senses(article).first())
    }

}
