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
        assertEquals(506, level1.size)
        assertTrue(level3.size > level1.size && level3.containsAll(level1))
        assertTrue(hsk.wordsUpTo(HskSystem.HSK2, 6).size > 4_000)
        assertEquals("7–9", HskSystem.HSK3.levelTitle(7))
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
}
