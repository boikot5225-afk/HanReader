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

    /** Chinese names the lexicon lacks are found by their surname and repetition. Own text, made-up names. */
    @Test
    fun chineseNamesOfTheBookAreFound() {
        val text = buildString {
            repeat(5) {
                append("邹晓岚走进教室，看见蒋沐正在写字。蒋沐抬头说：“你来了。”邹晓岚点点头。\n")
                append("桌上有一张纸，纸上写着几行字。高兴的孩子们在外面玩。\n")
            }
        }
        val book = com.hanreader.app.epub.TxtBook(text.toByteArray(), "x")
        val words = BookAnalyzer.analyze(1, book, engine.segmenter, engine.dictionary).words
        val found = words.filter { it.ignored }.map { it.word }.toSet()
        assertTrue(found.toString(), "邹晓岚" in found && "蒋沐" in found)
        // Not names: a measure word after a numeral, a surname-like adjective, a name plus a verb.
        assertTrue(found.toString(), found.none { it.startsWith("张") || it.startsWith("高") || it.length > 3 })
        assertFalse("邹晓" in found)
        // Kept whole once known, like the reader's own names.
        val kept = engine.segmenter.segment("邹晓岚看见蒋沐。", found).filter { it.kind == TokenKind.HAN }.map { it.text }
        assertEquals(listOf("邹晓岚", "看见", "蒋沐"), kept)
        // The characters of the names are not counted as words of the book.
        assertTrue(words.none { it.word == "邹" && !it.ignored })
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
        val names = listOf(com.hanreader.app.UserName("雷蒙德", 1, "zh-CN"), com.hanreader.app.UserName("Sara", 2, "es-ES"))
        val b = Backup(1, emptyList(), emptyList(), emptyList(), null, names)
        assertEquals(names, BackupFormat.decode(BackupFormat.encode(b)).userNames)
        assertNull(BackupFormat.decode(BackupFormat.encode(b.copy(userNames = null))).userNames)
        // A copy from before languages: names are plain strings, their language comes from the script.
        val old = BackupFormat.encode(b.copy(userNames = null)).replace("\"version\":3", "\"version\":2")
            .replace("\"cards\"", "\"userNames\":[\"莉娜\",\"Lima\"],\"cards\"")
        assertEquals(listOf("zh-CN" to "莉娜", "es-ES" to "Lima"), BackupFormat.decode(old).userNames!!.map { it.language to it.word })
    }

    @Test
    fun namesNextToAMiddleDot() {
        val text = "索琪丹·莫琳塔先生在一家公司工作。"
        val toks = engine.segmenter.segment(text)
        val runs = DotNames.runs(toks, { it.text }, { it.kind == TokenKind.HAN }, engine.dictionary)
        val found = runs.map { r -> r.joinToString("") { toks[it].text } }
        assertEquals(listOf("索琪丹", "莫琳塔"), found) // 先生 is an ordinary word: not part of the name
        // Ordinary words around a dot are not names.
        val t2 = "中国·北京"
        val toks2 = engine.segmenter.segment(t2)
        assertTrue(DotNames.runs(toks2, { it.text }, { it.kind == TokenKind.HAN }, engine.dictionary).isEmpty())
    }
}
