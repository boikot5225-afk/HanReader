package com.hanreader.app.en

import com.hanreader.app.lang.*
import org.junit.Assert.*
import org.junit.Test

/** Own synthetic diagnostics; literary holdout references are private and frozen separately. */
class EnReadingComfortTest {
    private val engine get() = EnBookContextAuditTest.engine
    private fun prepared(text: String) = engine.chapter(listOf(text),TextOptions())(text)
    private fun entry(text: String, word: String): LexEntry {
        val p=prepared(text);val i=p.tokens.indexOfFirst { it.text==word };assertTrue(word,i>=0)
        return engine.cardAnalyses(p.tokens,text,i,i).first().entry
    }
    @Test fun separatedExpressionsUseAttestedLemmasAtBothEnds() {
        for ((text,key) in listOf("She turned it off." to "turn off", "He picked it up." to "pick up",
            "We called it off." to "call off", "They took it away." to "take away", "Do not give me up." to "give up")) {
            val p=prepared(text)
            val head=p.tokens.indexOfFirst { it.pinyin==key };assertTrue(text,head>=0)
            val range=engine.phraseAt(p.tokens,text,head)!!
            assertEquals(key,engine.phraseKey(p.tokens,text,range.first,range.last))
            for (index in listOf(head,range.last)) assertEquals(text,key,engine.cardAnalyses(p.tokens,text,index,index).first().entry.lemma)
            assertEquals(key,engine.cardAnalyses(p.tokens,text,range.first,range.last).first().entry.lemma)
        }
    }
    @Test fun objectRetainsItsOwnCardAndHint() {
        val text="She turned it off.";val p=prepared(text);val i=p.tokens.indexOfFirst { it.text=="it" }
        assertNotEquals(IN_PHRASE,p.tokens[i].pinyin)
        assertNull(engine.phraseAt(p.tokens,text,i))
        assertEquals("it",engine.cardAnalyses(p.tokens,text,i,i).first().entry.lemma)
        assertNotNull(engine.glossShort(p.tokens[i]))
    }
    @Test fun particleHasNoDuplicateHintAndRetainsExpressionCard() {
        val text="She turned it off.";val p=prepared(text);val i=p.tokens.indexOfFirst { it.text=="off" }
        assertNull(engine.glossShort(p.tokens[i]))
        val entry = engine.cardAnalyses(p.tokens,text,i,i).first().entry
        assertEquals("turn off",engine.cardLemma(engine.cardAnalyses(p.tokens,text,i,i)))
        assertTrue(engine.cardTranslation("off",entry.key)!!.startsWith(engine.senses(entry).first()))
    }
    @Test fun prepositionalComplementsAndBodyTurnsAreNotSeparableFrames() {
        for (text in listOf("Look after him.","Look him in the eye.","She turned her back.","He put it on the table.","He turned it in his hands.")) {
            val p=prepared(text)
            assertTrue(text,p.tokens.none { it.pinyin in setOf("look in","turn back","put on","turn in") })
        }
    }
    @Test fun longerConstructionsAreNotTruncatedToAnUnrelatedTwoWordMeaning() {
        for ((text, key) in listOf("He gave them up to the guards." to "give up",
            "She put it down to luck." to "put down", "He took it out on me." to "take out")) {
            assertTrue(text,prepared(text).tokens.none { it.pinyin == key })
        }
    }
    @Test fun gapsNeverCrossPunctuationOrNewlines() {
        for (text in listOf("Turn it, off we go.","Turn it\noff.","Pick him — up there.")) {
            val p=prepared(text)
            assertTrue(text,p.tokens.none { it.pinyin in setOf("turn off","pick up") })
        }
    }
    @Test fun neighbouringExpressionsAndAdjacentExpressionsBothSurvive() {
        val text="Turn it off and pick it up, then look up the word.";val p=prepared(text)
        for (key in listOf("turn off","pick up","look up")) assertTrue(key,p.tokens.any { it.pinyin==key })
    }
    @Test fun dictionarySenseLabelsSelectMilitaryUnitsWithEvidence() {
        val e=entry("The captain ordered his company forward.","company")
        assertTrue(e.contextMeaning.orEmpty().contains("рота"))
        assertTrue(engine.senses(e).first().contains("рота"))
        assertTrue(e.source.orEmpty().contains("Викисловарь"))
    }
    @Test fun militaryWordsDoNotOverrideBusinessOrOrdinaryGroups() {
        assertNull(entry("The captain owned a company.","company").contextMeaning)
        assertNull(entry("The company sold rifles to the army.","company").contextMeaning)
        assertNull(entry("We enjoyed the company of our guests.","company").contextMeaning)
        assertNull(entry("A company waited near the soldier.","company").contextMeaning)
    }
    @Test fun numericalMeasurementsDifferFromBodyParts() {
        assertTrue(engine.senses(entry("The wall was twenty feet high.","feet")).first().contains("фут"))
        assertTrue(engine.senses(entry("The roof was 20 feet above us.","feet")).first().contains("фут"))
        assertNull(entry("His two feet were cold.","feet").contextMeaning)
        assertNull(entry("He had two feet below the blanket.","feet").contextMeaning)
    }
    @Test fun weaponsDifferFromEmbraces() {
        assertTrue(engine.senses(entry("The soldiers laid down their arms.","arms")).first().contains("оруж"))
        assertNull(entry("He held the child in his arms.","arms").contextMeaning)
    }
    @Test fun riverEdgesUseTheirLabelWithoutChangingFinancialBanks() {
        assertTrue(engine.senses(entry("They reached the bank of the river.","bank")).first().contains("берег"))
        assertNull(entry("The bank lent money near the river.","bank").contextMeaning)
    }
    @Test fun opposedContextsDoNotContaminateDictionaryCaches() {
        repeat(3) {
            assertTrue(engine.senses(entry("The wall was ten feet high.","feet")).first().contains("фут"))
            assertNull(entry("Her feet were cold.","feet").contextMeaning)
            assertEquals("анат. ступня, стопа, нога в значении «ступня»",engine.dictionary.analyses("foot").first { it.entry.pos=="noun" }.entry.russian.first())
        }
    }
    @Test fun evidenceCannotInventRussianOrChooseCompetingLabels() {
        assertNull(EnSenseEvidence.select(listOf(EnTranslation("unit of measure",emptyList())),"feet",listOf("ten","feet","high"),listOf("ten","foot","high"),1,true))
        assertNull(EnSenseEvidence.select(listOf(EnTranslation("unit of measure",listOf("a")),EnTranslation("unit of length",listOf("b"))),"feet",listOf("ten","feet","high"),listOf("ten","foot","high"),1,true))
    }
}
