package com.hanreader.app.en

import com.hanreader.app.lang.*
import com.hanreader.app.zh.TokenKind
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Original synthetic examples; never depends on a user's complete book. */
class EnBookContextAuditTest {
    companion object {
        val engine by lazy {
            val dir=File("src/main/assets/en")
            EnEngine(EnDictionary.read(File(dir,"dict.tsv").inputStream(),File(dir,"forms.tsv").inputStream(),File(dir,"ranks.tsv").inputStream(),File(dir,"manual.tsv").inputStream()),
                rules=File(dir,"context.tsv").useLines { ContextRules.parse(it) })
        }
    }
    private fun card(text:String,word:String):Pair<com.hanreader.app.reader.RToken,LexEntry> {
        val p=engine.chapter(listOf(text),TextOptions())(text)
        val i=p.tokens.indexOfFirst { it.text==word };assertTrue(word,i>=0)
        return p.tokens[i] to engine.cardAnalyses(p.tokens,text,i,i).first().entry
    }
    private fun primary(text:String,word:String)=engine.senses(card(text,word).second).first()
    @Test fun ordinaryNounsNeverBecomeLowercaseSurnames() {
        assertFalse(engine.dictionary.analyses("arms").any { it.entry.word=="Arms" })
        assertFalse(engine.dictionary.analyses("heard").any { it.entry.word=="Heard" })
        assertTrue(engine.dictionary.analyses("Heard").any { it.entry.word=="Heard" })
        assertTrue(primary("He folded his arms.","arms").contains("рука"))
    }
    @Test fun attestedPastFormsBeatUnrelatedBaseWords() {
        for((form,base) in listOf("rose" to "rise","spoke" to "speak","fell" to "fall","heard" to "hear","wore" to "wear")) {
            assertEquals(form,base,card("He $form quietly.",form).second.lemma)
        }
        assertEquals("rose",card("She held a rose.","rose").second.lemma)
        assertEquals("saw",card("He has a saw.","saw").second.lemma)
    }
    @Test fun nounPluralLemmaPreservesItsRussianArticle() {
        val men=card("The men arrived.","men").second
        assertEquals("man",men.lemma)
        assertTrue(men.ru.first().contains("люди"))
        assertEquals("woman",card("The women arrived.","women").second.lemma)
        assertFalse(engine.dictionary.analyses("men").any { it.entry.word=="Welshman" })
    }
    @Test fun infinitivesUseVerbReadingsAndAmbiguousImperativesKeepAlternatives() {
        assertEquals("гл.",card("It is hard to live.","live").second.pos)
        assertEquals("гл.",card("Ready to spring at him.","spring").second.pos)
        val text="Lay the book down."
        val t=engine.chapter(listOf(text),TextOptions())(text).tokens
        assertTrue(engine.cardAnalyses(t,text,0,0).any { it.entry.lemma=="lay" })
        assertEquals("find",card("They found themselves outside.","found").second.lemma)
    }
    @Test fun possessiveBeforeAnAdjectiveAndNounIsNotIs() {
        val possessive=card("The colonel’s arresting sign was clear.","colonel’s").second
        assertEquals("colonel",possessive.lemma)
        assertTrue(primary("The colonel’s arresting sign was clear.","colonel’s").contains("полковник"))
        assertTrue(card("The colonel’s arresting him.","colonel’s").second.en.contains("colonel is"))
    }
    @Test fun inflectedVerbPlusObjectIsNotAnImperativeFormula() {
        val p=engine.chapter(listOf("He had given me a book."),TextOptions())("He had given me a book.")
        val i=p.tokens.indexOfFirst { it.text=="given" }
        assertEquals("give",engine.cardAnalyses(p.tokens,p.text,i,i).first().entry.lemma)
        assertNull(engine.phraseAt(p.tokens,p.text,i))
    }
    @Test fun russianContractionExplanationsKeepCompleteExpansions() {
        val c=card("I'd written.","I'd").second
        assertEquals(listOf("I had"),c.en)
        assertTrue(c.ru.first().startsWith("я"))
        assertTrue(c.ru.first().contains("перфект"))
        assertNull(card("I'd written.","I'd").first.contextMeaning)
        val ambiguous=card("I'd read.","I'd").second
        assertEquals(setOf("I had","I would"),ambiguous.en.toSet())
        assertEquals(2,ambiguous.ru.size)
    }
    @Test fun nounFutureContractionsPreserveTheNoun() {
        val c=card("The body’ll rise.","body’ll").second
        assertEquals(listOf("body will"),c.en)
        assertTrue(c.ru.first().startsWith("тело"))
    }
    @Test fun hyphenFallbackUsesOnlyAttestedOpenCompounds() {
        assertTrue(primary("He opened his cartridge-box.","cartridge-box").contains("патронташ"))
        assertTrue(engine.dictionary.analyses("re-cov-er").isEmpty())
    }
    @Test fun explicitLocalAdditionsExposeTheirRealSource() {
        val p=card("He carried a field-glass.","field-glass").second
        assertTrue(p.ru.first().contains("бинокль"))
        assertEquals("Локальное дополнение словаря",p.source)
        assertTrue(engine.cardTranslation("at hand")!!.contains("поблизости"))
    }
    @Test fun contextualSensesNeverEnterLexicalCaches() {
        repeat(3) {
            assertEquals("приносить плоды",primary("The trees bear fruit.","bear"))
            assertTrue(primary("A bear slept.","bear").contains("медведь"))
            assertTrue(engine.dictionary.analyses("bear").first { it.entry.pos=="verb" }.entry.russian.none { it=="приносить плоды" })
        }
    }
    @Test fun militaryChargesDoNotChangeElectricalCharges() {
        assertEquals("атака",primary("The commander ordered a charge.","charge"))
        assertNull(card("The electrical charge remained.","charge").first.contextMeaning)
    }
    @Test fun physicalAndFalseLyingRemainDifferent() {
        assertEquals("лежать; покоиться",primary("He lay on the ground.","lay"))
        assertNull(card("Do not lie about the money.","lie").first.contextMeaning)
    }
    @Test fun sentencesAndNewlinesBoundTheSenseWindow() {
        assertNull(card("The army stayed. An officer signed a form.","officer").first.contextMeaning)
        assertNull(card("The army stayed\nAn officer signed a form.","officer").first.contextMeaning)
        assertEquals("офицер",primary("The officer, in uniform, arrived.","officer"))
    }
    @Test fun idiomSensesAgreeAcrossHintHeadAndWholeSelection() {
        val text="His expression passed away."
        val p=engine.chapter(listOf(text),TextOptions())(text);val a=p.tokens.indexOfFirst { it.text=="passed" };val b=p.tokens.indexOfFirst { it.text=="away" }
        val head=engine.cardAnalyses(p.tokens,text,a,a).first().entry
        val full=engine.cardAnalyses(p.tokens,text,a,b).first().entry
        assertEquals("исчезать; проходить",engine.glossShort(p.tokens[a]))
        assertEquals(engine.senses(head).first(),engine.senses(full).first())
        assertTrue(engine.cardTranslation("pass away")!!.contains("скончаться"))
    }
    @Test fun poetryAndCopularStatesDoNotBecomeImperativesOrRareVerbs() {
        assertEquals("fall",card("Fell the rain on the empty field.","Fell").second.lemma)
        assertEquals("прил.",card("The tree was dead.","dead").second.pos)
        assertEquals("гл.",card("The men work.","work").second.pos)
    }
    @Test fun adjectiveChainsRemainModifiersUntilTheNoun() {
        assertEquals("прил.",card("A skilled federal officer arrived.","federal").second.pos)
        assertEquals("сущ.",card("The right grave was empty.","grave").second.pos)
    }
    @Test fun modalPossibilityAndNounStrengthStaySeparate() {
        assertTrue(primary("He might leave.","might").contains("мочь"))
        assertEquals("сущ.",card("Her might was great.","might").second.pos)
        assertTrue(primary("Her might was great.","might").contains("мощь"))
        assertEquals("сущ.",card("Might was his only advantage.","Might").second.pos)
    }

    @Test fun englishRussianTranslationsRemainAvailableBesideNativeDefinitions() {
        val noun=engine.dictionary.analyses("watch").first { it.entry.word=="watch" && it.entry.pos=="noun" }.entry
        assertTrue(noun.russian.first().contains("наблюдение"))
        assertTrue(noun.russian.any { it.contains("часы") })
        assertTrue(noun.russianSource!!.contains("английского"))
    }

    @Test fun discourseCommaDoesNotPretendToBeAnAdjacentSenseCue() {
        assertNull(card("Still, you can leave.","Still").first.contextMeaning)
        assertEquals("всё ещё; по-прежнему",card("You are still in the room.","still").first.contextMeaning)
    }

}
