package com.hanreader.app.en

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Exercises the generated production assets, not a parallel hand-written dictionary. */
class EnDictionaryTest {
    companion object {
        val dictionary by lazy {
            EnDictionary.read(File("src/main/assets/en/dict.tsv").inputStream(), File("src/main/assets/en/forms.tsv").inputStream(),
                File("src/main/assets/en/ranks.tsv").inputStream())
        }
    }

    @Test fun irregularVerbsAndPluralsReachTheirRussianArticles() {
        val went = dictionary.analyses("went").first { it.entry.word == "go" && it.entry.pos == "verb" }
        assertTrue(went.entry.russian.isNotEmpty())
        assertTrue("past" in went.tags)
        val children = dictionary.analyses("children").first { it.entry.word == "child" && it.entry.pos == "noun" }
        assertTrue("plural" in children.tags)
        assertTrue(children.entry.russian.isNotEmpty())
        assertTrue(dictionary.analyses("mice").any { it.entry.word == "mouse" })
        assertTrue(dictionary.analyses("written").any { it.entry.word == "write" && "participle" in it.tags })
    }

    @Test fun homographsKeepTheirPartsOfSpeechAndMeaningsSeparate() {
        val saw = dictionary.analyses("saw")
        val noun = saw.first { it.entry.word == "saw" && it.entry.pos == "noun" }
        val verb = saw.first { it.entry.word == "see" && it.entry.pos == "verb" }
        assertTrue(noun.entry.russian.any { "пила" in it })
        assertTrue(verb.entry.russian.any { "видеть" in it })
        assertFalse(noun.entry.russian.any { "видеть" in it })
    }

    @Test fun lowerCaseGoNeverReadsTheAcronymGO() {
        assertFalse(dictionary.analyses("go").any { it.entry.word == "GO" })
        assertTrue(dictionary.analyses("go").any { it.entry.word == "go" && it.entry.pos == "verb" })
        assertTrue(dictionary.analyses("I").any { it.entry.pos == "pron" && it.entry.russian.any { r -> r == "я" } })
    }

    @Test fun britishSpellingUsesASourcedRussianTranslation() {
        val entry = dictionary.analyses("organise").first { it.entry.word == "organize" }.entry
        assertTrue(entry.russian.any { "организ" in it })
        assertTrue(entry.translations.any { it.sense.isNotBlank() && it.words.isNotEmpty() })
        assertEquals("Английский Викисловарь: русские переводы", entry.russianSource)
    }

    @Test fun inflectedPhrasesKeepTheirDictionaryForm() {
        assertTrue(dictionary.analyses("looked up").any { it.entry.word == "look up" && it.entry.russian.isNotEmpty() })
        assertTrue(dictionary.analyses("turned off").any { it.entry.word == "turn off" && it.entry.russian.isNotEmpty() })
    }

    @Test fun contractionsAreNotReducedToThePronoun() {
        val result = dictionary.lookup("I’d")
        assertEquals(setOf("I had", "I would"), result.form.expansions.map { it.text }.toSet())
        assertFalse(result.analyses.any { it.entry.word == "I" })
    }

    @Test fun possessivePluralHasTheSameLexicalBaseAndNoSingularAuxiliaries() {
        val result = dictionary.lookup("teachers’")
        assertTrue(result.possessiveAnalyses.any { it.entry.word == "teacher" && "plural" in it.tags })
        assertTrue(result.form.expansions.isEmpty())
        assertEquals("teacher", dictionary.lookup("teacher’s").form.possessive?.base)
    }

    @Test fun pronunciationAndFrequencySurviveCompilation() {
        val entry = dictionary.analyses("go").first { it.entry.word == "go" && it.entry.pos == "verb" }.entry
        assertTrue(entry.pronunciations.isNotEmpty())
        assertTrue(entry.rank > 0)
        assertTrue(entry.english.isNotEmpty())
    }
}
