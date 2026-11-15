package com.hanreader.app.en

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.lang.IN_PHRASE
import com.hanreader.app.reader.KnownMatcher
import com.hanreader.app.zh.TokenKind
import org.junit.Assert.*
import org.junit.Test

class EnEngineTest {
    companion object { val engine by lazy { EnEngine(EnDictionaryTest.dictionary) } }
    private fun prepare(text: String) = engine.chapter(listOf(text), TextOptions())(text)
    private fun card(text: String, word: String) = prepare(text).let { p ->
        val i = p.tokens.indexOfFirst { it.text == word }
        assertTrue("Token $word in $text", i >= 0)
        engine.cardAnalyses(p.tokens, text, i, i)
    }

    @Test fun tokensCoverTheOriginalTextAndKeepApostrophes() {
        val text = "He read 'books' and teachers’ notes. I'm here."
        val p = prepare(text)
        assertEquals(text, p.text)
        assertEquals(text, p.shown)
        assertEquals(text, p.tokens.joinToString("") { it.text })
        p.tokens.forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
        assertTrue(p.tokens.any { it.text == "teachers’" && it.kind == TokenKind.HAN })
        assertTrue(p.tokens.any { it.text == "books" })
        assertTrue(p.tokens.any { it.text == "I'm" && it.kind == TokenKind.HAN })
    }

    @Test fun aPastFormAndANounUseDifferentCardsAndHints() {
        val verb = card("I saw a bird.", "saw").first()
        val noun = card("He has a saw.", "saw").first()
        assertEquals("see", verb.entry.lemma)
        assertTrue(verb.entry.isVerb)
        assertEquals("saw", noun.entry.lemma)
        assertEquals("сущ.", noun.entry.pos)
        val p = prepare("He has a saw.")
        val t = p.tokens.first { it.text == "saw" }
        assertTrue(engine.glossShort(t)?.contains("пила") == true)
        assertNull(t.contextMeaning)
    }

    @Test fun leftHandIsNotAutomaticallyTheVerbLeave() {
        val all = card("Her left hand hurt.", "left")
        assertTrue(all.first().entry.lemma != "leave")
    }

    @Test fun irregularFormsAreLearnedAsTheirBaseWords() {
        assertEquals("go", card("She went slowly.", "went").first().entry.lemma)
        assertEquals("child", card("The children laughed.", "children").first().entry.lemma)
        assertEquals("go", engine.lemma("went"))
        assertTrue(KnownMatcher(setOf("go", "child"), engine).isKnown("went"))
        assertTrue(KnownMatcher(setOf("go", "child"), engine).isKnown("children"))
    }

    @Test fun contractionsKeepTheirFullExpansionAndUseConservativeContext() {
        val unresolved = card("I'd read.", "I'd").first()
        assertEquals("I'd", unresolved.entry.lemma)
        assertTrue(unresolved.entry.en.contains("I had"))
        assertTrue(unresolved.entry.en.contains("I would"))
        val would = card("I'd go.", "I'd").first()
        assertEquals(listOf("I would"), would.entry.en)
        val had = card("I'd written.", "I'd").first()
        assertEquals(listOf("I had"), had.entry.en)
        assertFalse(engine.cardTranslation("I'm").isNullOrBlank())
        assertNotEquals("I", engine.lemma("I'm"))
    }

    @Test fun pluralPossessivesUseTheNounAndNotSingularAuxiliaries() {
        val noun = card("The teachers’ books fell.", "teachers’").first()
        assertEquals("teacher", noun.entry.lemma)
        assertTrue(noun.grammar.contains("притяжательная"))
    }

    @Test fun adjacentPhrasalVerbsHaveOneHintAndOneDictionaryForm() {
        val text = "She looked up the word."
        val p = prepare(text)
        val head = p.tokens.indexOfFirst { it.text == "looked" }
        val particle = p.tokens.indexOfFirst { it.text == "up" }
        assertEquals("look up", p.tokens[head].pinyin)
        assertEquals(IN_PHRASE, p.tokens[particle].pinyin)
        assertEquals("look up", engine.cardAnalyses(p.tokens, text, head, head).first().entry.lemma)
        assertNull(engine.glossShort("up", IN_PHRASE))
        assertEquals(head..particle, engine.phraseAt(p.tokens, text, head))
    }

    @Test fun phrasesNeverCrossPunctuationOrParagraphBreaks() {
        for (text in listOf("Look, up there.", "Look\nup there.")) {
            val p = prepare(text)
            val a = p.tokens.indexOfFirst { it.text == "Look" }
            val b = p.tokens.indexOfFirst { it.text == "up" }
            assertNull(engine.phraseKey(p.tokens, text, a, b))
        }
    }

    @Test fun searchNormalizesCaseAndApostrophesWithoutMovingOffsets() {
        val text = "  I'M Here.  "
        assertEquals("  i'm here.  ", engine.searchForm(text))
        assertEquals(text.length, engine.searchForm(text).length)
        assertEquals("i'm", engine.searchForm("I’m"))
    }

    @Test fun bookWordsAreCountedByTheFormsSeenInContext() {
        val book = TxtBook("I went. They went. I go. The children laughed.".toByteArray(), "sample")
        val analysis = engine.analyze(1L, book)
        assertEquals(3, analysis.words.first { it.word == "go" }.count)
        assertTrue(analysis.words.any { it.word == "child" })
        assertFalse(engine.isName("I"))
    }

    @Test fun inflectedExamplesCanBeHiddenWithoutMaskingOtherWords() {
        val text = "She went and goes."
        val ranges = engine.occurrences(text, "go")
        assertEquals(listOf("went", "goes"), ranges.map { text.substring(it) })
        assertTrue(engine.occurrences("The catalog is here.", "cat").isEmpty())
        val phrase = "She looked up the word."
        assertEquals(listOf("looked up"), engine.occurrences(phrase, "look up").map { phrase.substring(it) })
    }

    @Test fun basicVerbTableComesFromTheActualDictionary() {
        val table = engine.conjugationTable("go")
        assertTrue(table.nonFinite.any { "went" in it })
        assertTrue(table.nonFinite.any { "gone" in it })
        assertTrue(table.nonFinite.any { "going" in it })
    }
}
