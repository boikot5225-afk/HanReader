package com.hanreader.app.reader

import com.hanreader.app.Backup
import com.hanreader.app.BackupFormat
import com.hanreader.app.BookWord
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Own sample text; the names are made up. */
class NamesTest {
    private val names = File("src/main/assets/zh/names.tsv").bufferedReader().useLines { ZhEngine.parseNames(it) }
    private val hsk = File("src/main/assets/zh/hsk.tsv").bufferedReader().useLines { HskLevels.parse(it) }
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0, hsk, names = names)

    @Test
    fun lexiconLoadsAndKeepsOrdinaryWordsOut() {
        assertTrue(names.size > 60_000)
        assertEquals(ZhEngine.NameKind.ORGANISATION, engine.nameKind("中国科学院"))
        assertNull(engine.nameKind("图书馆"))
        assertNull(engine.nameKind("一介武夫")) // an idiom Jieba tags as a name
        assertNull(engine.nameKind("中国")) // an HSK word stays learnable
        assertTrue(VocabularyStats.isName("中国科学院", engine))
    }

    @Test
    fun extraWordsStayWhole() {
        val text = "索琪丹先生每天早上去图书馆。"
        val plain = engine.segmenter.segment(text).filter { it.kind == TokenKind.HAN }.map { it.text }
        assertFalse("索琪丹" in plain)
        val kept = engine.segmenter.segment(text, setOf("索琪丹")).filter { it.kind == TokenKind.HAN }.map { it.text }
        assertTrue("索琪丹" in kept)
        assertTrue("图书馆" in kept)
        // Pinyin is built from its characters.
        val tok = engine.segmenter.segment(text, setOf("索琪丹")).first { it.text == "索琪丹" }
        assertEquals("suǒqídān", engine.pinyinOf(tok))
    }

    @Test
    fun namesAreNotRecommended() {
        val text = "中国科学院的老师终于回来了。中国科学院很大。"
        val ch = ChapterContent(0, "", listOf(ReaderBlock.Text(text, false, engine.segmenter.segment(text).map {
            RToken(it.text, engine.pinyinOf(it), it.kind, it.start, it.end, it.entry?.hasDefinition == true)
        })), 0, 0)
        val list = Recommendations.forChapter(ch, KnownMatcher(emptySet(), engine), emptySet(), emptySet(),
            mapOf("中国科学院" to BookWord(1, "中国科学院", 50, 0)), engine, ReaderLevel(1, true), 10)
        assertFalse(list.any { it.word == "中国科学院" })
    }

    @Test
    fun backupKeepsUserNames() {
        val b = Backup(1, emptyList(), emptyList(), emptyList(), null, listOf("雷蒙德", "莉娜"))
        assertEquals(listOf("雷蒙德", "莉娜"), BackupFormat.decode(BackupFormat.encode(b)).userNames)
        assertNull(BackupFormat.decode(BackupFormat.encode(b.copy(userNames = null))).userNames)
    }
}
