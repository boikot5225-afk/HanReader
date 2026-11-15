package com.hanreader.app.en

import org.junit.Assert.*
import org.junit.Test

class EnFormsTest {
    private fun expansions(word: String) = EnForms.parse(word).expansions.map { it.text }.toSet()

    @Test fun apostrophesAndCaseNormalizeWithoutLosingTheDisplayName() {
        assertEquals("i'm", EnForms.key(" I’m "))
        assertEquals("John", EnForms.parse("John’s").possessive?.base)
    }

    @Test fun negativesKeepTheAuxiliaryAndNot() {
        assertEquals(setOf("do not"), expansions("don't"))
        assertEquals(setOf("does not"), expansions("doesn’t"))
        assertEquals(setOf("can not"), expansions("can't"))
        assertEquals(setOf("will not"), expansions("won't"))
        assertEquals(setOf("shall not"), expansions("shan't"))
        assertEquals(setOf("might not"), expansions("mightn't"))
    }

    @Test fun auxiliariesHaveExplicitAlternatives() {
        assertEquals(setOf("I am"), expansions("I'm"))
        assertEquals(setOf("we have"), expansions("we've"))
        assertEquals(setOf("they are"), expansions("they're"))
        assertEquals(setOf("you will"), expansions("you'll"))
        assertEquals(setOf("I had", "I would"), expansions("I'd"))
        assertEquals(setOf("he is", "he has"), expansions("he's"))
    }

    @Test fun compoundContractionsDoNotInventHadHave() {
        assertEquals(setOf("I would have"), expansions("I'd've"))
        assertEquals(setOf("could not have"), expansions("couldn’t’ve"))
    }

    @Test fun singularPossessivesRemainAmbiguousWithoutContext() {
        val form = EnForms.parse("teacher's")
        assertEquals("teacher", form.possessive?.base)
        assertFalse(form.possessive!!.pluralSpelling)
        assertEquals(setOf("teacher is", "teacher has"), expansions("teacher's"))
    }

    @Test fun pluralAndNamesEndingInSKeepTheirBase() {
        assertEquals("teachers", EnForms.parse("teachers’").possessive?.base)
        assertTrue(EnForms.parse("teachers’").possessive!!.pluralSpelling)
        assertEquals("James", EnForms.parse("James'").possessive?.base)
        assertTrue(expansions("teachers'").isEmpty())
    }

    @Test fun itsAndLetsHaveTheirOwnRules() {
        assertNull(EnForms.parse("it's").possessive)
        assertEquals(setOf("it is", "it has"), expansions("it's"))
        assertEquals(setOf("let us"), expansions("let's"))
        assertNull(EnForms.parse("lets").possessive)
    }

    @Test fun ordinaryApostrophesAndUnpunctuatedWordsAreNotGuessed() {
        assertTrue(expansions("O'Neill").isEmpty())
        assertTrue(expansions("o'clock").isEmpty())
        assertTrue(expansions("dont").isEmpty())
        assertTrue(expansions("paintn't").isEmpty())
        assertNull(EnForms.parse("its").possessive)
    }
}
