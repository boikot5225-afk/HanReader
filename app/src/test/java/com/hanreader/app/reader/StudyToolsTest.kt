package com.hanreader.app.reader

import com.hanreader.app.Highlight
import com.hanreader.app.ScriptMode
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhScript
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tone colours, simplified/traditional script, «Не знаю» marks and highlights; sample text written for the test. */
class StudyToolsTest {
    private val script = File("src/main/assets/zh/script.tsv").inputStream().use { ZhScript.parse(it) }
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0, script = script)

    @Test
    fun tonesFromMarks() {
        assertEquals(listOf(1, 2, 3, 4, 5), listOf("mā", "má", "mǎ", "mà", "ma").map { Tones.of(it) })
        assertEquals(3, Tones.of("nǚ"))
        assertEquals(2, Tones.of("Lǘ"))
    }

    @Test
    fun syllablesLineUpWithCharacters() {
        val ch = ChapterBuilder.build(TxtBook("我们明天去图书馆。".toByteArray(), "x"), 0, engine)
        val tokens = (ch.blocks.first() as ReaderBlock.Text).tokens.filter { it.kind == TokenKind.HAN }
        for (t in tokens) {
            assertEquals(t.text, t.text.length, t.syllables.size)
            assertEquals(t.pinyin, t.syllables.joinToString(""))
        }
        assertEquals(listOf(3, 5), tokens.first { it.text == "我们" }.syllables.map { Tones.of(it) })
    }

    @Test
    fun simplifiedAndTraditional() {
        assertEquals("頭髮", script.toTraditional("头发"))
        assertEquals("發展", script.toTraditional("发展"))
        assertEquals("头发", script.toSimplified("頭髮"))
        assertTrue(script.looksTraditional("他們今天去圖書館看書，頭髮很長。"))
        assertFalse(script.looksTraditional("他们今天去图书馆看书，头发很长。"))
    }

    /** A traditional book is segmented in simplified form and drawn as the reader chose; offsets do not move. */
    @Test
    fun traditionalBookReadsWithTheSimplifiedDictionary() {
        val trad = "他們今天去圖書館看書。"
        val book = TxtBook(trad.toByteArray(), "x")
        val asBook = ChapterBuilder.build(book, 0, engine, script = ScriptMode.AS_BOOK).blocks.first() as ReaderBlock.Text
        assertEquals("他们今天去图书馆看书。", asBook.text)
        assertEquals(trad, asBook.shown)
        val lib = asBook.tokens.first { it.text == "图书馆" }
        assertTrue(lib.defined)
        assertEquals("圖書館", lib.display)
        assertEquals("圖書館", trad.substring(lib.start, lib.end))
        val simple = ChapterBuilder.build(book, 0, engine, script = ScriptMode.SIMPLIFIED).blocks.first() as ReaderBlock.Text
        assertEquals("图书馆", simple.tokens.first { it.text == "图书馆" }.display)
        // A simplified book shown in traditional characters.
        val s = ChapterBuilder.build(TxtBook("她的头发很长。".toByteArray(), "x"), 0, engine, script = ScriptMode.TRADITIONAL).blocks.first() as ReaderBlock.Text
        assertEquals("她的頭髮很長。", s.shown)
        assertEquals("头发", s.tokens.first { it.display == "頭髮" }.text)
        // Search finds traditional text with simplified input.
        assertTrue(BookSearch.textMatches(script.toSimplified(trad), script.toSimplified("圖書館")).isNotEmpty())
    }

    /** «Не знаю» wins over the level, learned cards and known parts. */
    @Test
    fun unknownMarkOverridesEverything() {
        val known = setOf("看", "着", "他", "们")
        assertTrue(KnownMatcher(known, engine).isKnown("看"))
        val marked = KnownWords(known - "看", setOf("看"))
        val m = KnownMatcher(marked, engine)
        assertFalse(m.isKnown("看"))
        assertTrue(m.isKnown("他"))
        // A compound of known words, marked «Не знаю» itself.
        val parts = setOf("走", "进")
        assertTrue(KnownMatcher(parts, engine).isKnown("走进"))
        assertFalse(KnownMatcher(KnownWords(parts, setOf("走进")), engine).isKnown("走进"))
    }

    @Test
    fun highlightsMapToTokensAndExport() {
        val text = "那天晚上一直在下雨。他站在门口，等一个不会来的人。"
        val block = (ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine).blocks.first() as ReaderBlock.Text)
        // The sentence around a word: from after the previous full stop to its own.
        val door = block.tokens.indexOfFirst { it.text == "门口" }
        val r = Highlights.sentence(block, door, door)
        assertEquals("他站在门口，等一个不会来的人。", text.substring(block.tokens[r.first].start, block.tokens[r.last].end))
        val start = block.tokens[r.first].start
        val h = Highlight(1, 7, 0, 0, start, text.length, 2, "ждёт напрасно", text.substring(start), "第一章")
        val (marked, noted) = Highlights.tokens(block, listOf(h))
        assertEquals(r.toSet(), marked.keys)
        assertEquals(setOf(r.last), noted)
        assertEquals(h, Highlights.at(listOf(h), 0, 0, block.tokens[door].start, block.tokens[door].end))
        assertEquals(null, Highlights.at(listOf(h), 0, 0, 0, 2))
        val md = Highlights.markdown("雨夜", "Автор", listOf(h))
        assertTrue(md, md.startsWith("# 雨夜\n"))
        assertTrue(md, "## 第一章\n" in md)
        assertTrue(md, "> 他站在门口，等一个不会来的人。\n" in md)
        assertTrue(md, "\nждёт напрасно\n" in md)
    }
}
