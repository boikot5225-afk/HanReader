package com.hanreader.app.reader

import com.hanreader.app.BookWord
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Own sample text. */
class RecommendationsTest {
    private val hsk = File("src/main/assets/zh/hsk.tsv").bufferedReader().useLines { HskLevels.parse(it) }
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0, hsk)

    private fun chapter(vararg texts: String) = ChapterContent(
        0, "", texts.map { t ->
            ReaderBlock.Text(t, false, engine.segmenter.segment(t).map {
                RToken(it.text, engine.pinyinOf(it), it.kind, it.start, it.end, it.entry?.hasDefinition == true)
            })
        }, 0, 0
    )

    private fun knownUpTo(level: Int): KnownMatcher {
        val words = hsk.wordsUpTo(HskSystem.HSK3, level).toHashSet()
        return KnownMatcher(words + KnownExpansion.expand(words, engine.dictionary), engine)
    }

    @Test
    fun levelChosenOrAutomatic() {
        assertEquals(ReaderLevel(3, auto = false), Recommendations.level(LevelMark(HskSystem.HSK3, 3), knownUpTo(1), hsk))
        // HSK 2.0 level 4 (about 1,200 words) is about HSK 3.0 level 2.
        assertEquals(ReaderLevel(2, auto = false), Recommendations.level(LevelMark(HskSystem.HSK2, 4), knownUpTo(1), hsk))
        // Nothing chosen: worked out from what is known.
        assertEquals(ReaderLevel(3, auto = true), Recommendations.level(LevelMark(HskSystem.HSK3, 0), knownUpTo(3), hsk))
        assertEquals(1, Recommendations.level(LevelMark(HskSystem.HSK3, 0), KnownMatcher(emptySet(), engine), hsk).hsk3)
    }

    @Test
    fun iPlusOneScoresHighest() {
        val plus1 = Recommendations.score(10, 2, wordLevel = 4, readerLevel = 3)
        val plus3 = Recommendations.score(10, 2, wordLevel = 6, readerLevel = 3)
        assertTrue(plus1 > plus3)
        // A rarer word one level up loses to a frequent one at the same level.
        assertTrue(Recommendations.score(40, 3, 4, 3) > Recommendations.score(2, 1, 4, 3))
    }

    @Test
    fun recommendsFrequentUnknownWordsNotNamesOrSaved() {
        val ch = chapter(
            "小明终于回到了北京。他决定先去图书馆。",
            "图书馆的老师很热情。小明终于找到了那本书。",
            "他决定以后每天都来图书馆，可是图书馆周末不开门。"
        )
        val known = knownUpTo(1)
        val book = mapOf("终于" to BookWord(1, "终于", 30, 0), "决定" to BookWord(1, "决定", 25, 0), "图书馆" to BookWord(1, "图书馆", 12, 0))
        val list = Recommendations.forChapter(ch, known, saved = setOf("决定"), names = setOf("小明"), bookWords = book,
            engine = engine, level = ReaderLevel(1, true), limit = 5)
        val words = list.map { it.word }
        assertTrue(words.isNotEmpty() && words.size <= 5)
        assertFalse("决定" in words) // already in the cards
        assertFalse("小明" in words) // a name
        assertFalse("北京" in words) // known at HSK 1
        assertTrue(words.none { known.isKnown(it) })
        val zhongyu = list.firstOrNull { it.word == "终于" }
        assertTrue(zhongyu != null && zhongyu.bookCount == 30 && zhongyu.chapterCount == 2)
        assertEquals(list.sortedByDescending { it.score }, list)
    }
}
