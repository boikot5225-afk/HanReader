package com.hanreader.app.reader

import com.hanreader.app.BookWord
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Own sample text. */
class BookVocabularyTest {
    private val hsk = File("src/main/assets/zh/hsk.tsv").bufferedReader().useLines { HskLevels.parse(it) }
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0, hsk)

    private fun chapter(vararg paragraphs: String): ChapterContent {
        val blocks = paragraphs.map { text ->
            ReaderBlock.Text(text, false, engine.segmenter.segment(text).map {
                RToken(it.text, engine.pinyinOf(it), it.kind, it.start, it.end, it.entry?.hasDefinition == true)
            })
        }
        return ChapterContent(3, "第三章", blocks, 0, paragraphs.sumOf { it.length })
    }

    private val sample = chapter(
        "小明终于到了北京。他在北京找到了一份工作。",
        "工作很忙，可是他终于习惯了。每天下班以后，他去图书馆看书。",
        "图书馆的书很多，他终于找到了喜欢的书。"
    )

    @Test
    fun chapterCandidatesAreFrequentUnknownWords() {
        val known = setOf("他", "了", "在", "到", "很", "的", "每天", "去", "看书", "书", "多", "一", "份", "可是", "找到", "喜欢", "以后")
        val list = VocabularyStats.chapter(sample, known, saved = setOf("工作"), ignored = emptySet(), engine = engine)
        val words = list.map { it.word }
        assertEquals("终于", words.first()) // three times in the chapter
        assertEquals(3, list.first().count)
        assertTrue("图书馆" in words)
        assertFalse("工作" in words) // already in the cards
        assertFalse("北京" in words) // proper noun
        assertFalse("小明" in words) // name without a definition
        assertTrue(words.none { it in known })
        assertEquals(sample.index, list.first().firstChapter)
    }

    @Test
    fun ignoredWordsAreSkipped() {
        val list = VocabularyStats.chapter(sample, emptySet(), emptySet(), setOf("终于"), engine)
        assertFalse(list.any { it.word == "终于" })
    }

    @Test
    fun firstSentenceComesFromTheChapter() {
        assertEquals("小明终于到了北京。", VocabularyStats.firstSentence(sample, "终于"))
        assertEquals("每天下班以后，他去图书馆看书。", VocabularyStats.firstSentence(sample, "图书馆"))
    }

    @Test
    fun bookStatsCoverageAndLevels() {
        val words = listOf(
            BookWord(1, "我", 50, 0), BookWord(1, "他们", 30, 0), BookWord(1, "终于", 10, 1),
            BookWord(1, "规矩", 5, 2), BookWord(1, "北京", 5, 0)
        )
        val stats = VocabularyStats.book(words, tokens = 100, known = setOf("我"), saved = emptySet(), engine = engine)
        assertEquals(50, stats.knownPercent)
        assertEquals(4, stats.unknownDistinct)
        assertEquals(listOf("他们", "终于", "规矩"), stats.candidates.map { it.word })
        // HSK 1 covers 我, 他们 and 北京 (85%); every level covers at least what the previous one does.
        assertEquals(85, stats.levels.first().percent)
        assertTrue(stats.levels.zipWithNext().all { (a, b) -> b.percent >= a.percent })
        assertEquals(1, stats.levelFor(85))
    }

    @Test
    fun transparentCompoundsCountAsKnown() {
        val m = KnownMatcher(setOf("看", "着", "走", "进", "一个", "人", "大", "难", "不", "死"), engine)
        assertTrue(m.isKnown("看着"))       // word + aspect particle
        assertTrue(KnownMatcher(setOf("跑", "进"), engine).isKnown("跑进")) // word + direction complement
        assertFalse(m.isKnown("大难不死"))   // idiom: never split
        assertFalse(m.isKnown("打电话"))     // listed in HSK: learned as such
        val jiebaOnly = ZhSegmenterTest.dict.lookup("一个人")
        if (jiebaOnly != null && !jiebaOnly.hasDefinition) assertTrue(m.isKnown("一个人"))
    }
}
