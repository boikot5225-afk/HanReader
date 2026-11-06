package com.hanreader.app.reader

import com.hanreader.app.Backup
import com.hanreader.app.BackupFormat
import com.hanreader.app.KnownWord
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnownWordsTest {
    private val hsk = File("src/main/assets/zh/hsk.tsv").bufferedReader().useLines { HskLevels.parse(it) }

    @Test
    fun hskLevelsUpTo() {
        assertEquals(1, hsk.hsk3("我"))
        val level1 = hsk.wordsUpTo(HskSystem.HSK3, 1)
        val level3 = hsk.wordsUpTo(HskSystem.HSK3, 3)
        assertTrue(level1.size in 500..560)
        assertTrue(level3.size > level1.size && level3.containsAll(level1))
        assertTrue(hsk.wordsUpTo(HskSystem.HSK2, 6).size > 4_000)
        assertEquals("7–9", HskSystem.HSK3.levelTitle(7))
    }

    /** The case from a real book: HSK 2.0 level 4 must cover common words and their obvious derivatives. */
    @Test
    fun hsk2Level4CoversCommonWords() {
        val listed = hsk.wordsUpTo(HskSystem.HSK2, 4).toHashSet()
        assertEquals(1, hsk.hsk2("说"))
        assertEquals(1, hsk.hsk2("没有"))
        assertEquals(1, hsk.hsk2("一点")) // erhua form 一点儿 without 儿
        val known = listed + KnownExpansion.expand(listed, ZhSegmenterTest.dict)
        listOf("他们", "我们", "说", "没有", "那些", "这个", "一个", "一些", "哪里", "人们").forEach {
            assertTrue("$it should be known at HSK 2.0 level 4", it in known)
        }
        // Not derived: meaning is not the sum of the parts.
        assertTrue("不过" !in KnownExpansion.expand(setOf("不", "过"), ZhSegmenterTest.dict))
        // Official 2012 lists: 150 + 150 + 300 + 600 words, give or take list quirks.
        assertTrue(listed.size in 1_150..1_300)
    }

    @Test
    fun coverageCountsWordOccurrences() {
        val engine = ZhEngine(ZhSegmenterTest.dict, 0)
        val text = "他今天去图书馆看书。他很喜欢图书馆。"
        val tokens = engine.segmenter.segment(text).map {
            RToken(it.text, engine.pinyinOf(it), it.kind, it.start, it.end, it.entry?.hasDefinition == true)
        }
        val chapter = ChapterContent(0, "", listOf(ReaderBlock.Text(text, false, tokens)), 0, text.length)
        val han = tokens.count { it.kind == com.hanreader.app.zh.TokenKind.HAN }
        val c = KnownCoverage.of(chapter, setOf("他", "今天", "去", "看书", "很", "喜欢"))
        assertEquals(han, c.total)
        assertEquals(han - 2, c.known) // 图书馆 twice
        assertEquals(1, c.unknownDistinct)
        assertEquals(KnownCoverage(0, 0, 0).percent, 0)
    }

    @Test
    fun backupKeepsKnownWordsAndOldFilesStillLoad() {
        val backup = Backup(1, emptyList(), emptyList(), emptyList(), listOf(KnownWord("我", "hsk3:1", 5), KnownWord("终于", "manual", 6)))
        val decoded = BackupFormat.decode(BackupFormat.encode(backup))
        assertEquals(backup.knownWords, decoded.knownWords)
        // A Lexicon backup has no knownWords: null means "keep the current ones".
        val lexicon = BackupFormat.decode(BackupFormat.encode(backup.copy(knownWords = null)))
        assertNull(lexicon.knownWords)
    }

    @Test
    fun russianMeaningsComeFirstOnCards() {
        val ru = File("src/main/assets/zh/ru.tsv").bufferedReader().useLines { ZhEngine.parseRussian(it) }
        assertTrue(ru.size > 11_000)
        val engine = ZhEngine(ZhSegmenterTest.dict, 0, hsk, ru)
        assertEquals(listOf("они"), engine.russianSenses("他们"))
        assertEquals("немедленно, тотчас, сразу же", engine.cardTranslation("立刻"))
        // No Russian entry: English CC-CEDICT gloss.
        assertTrue(engine.russianSenses("终于").isEmpty())
        assertTrue(engine.cardTranslation("终于")!!.contains("finally"))
    }
}
