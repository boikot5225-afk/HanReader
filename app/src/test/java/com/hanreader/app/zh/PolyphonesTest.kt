package com.hanreader.app.zh

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.reader.ChapterBuilder
import com.hanreader.app.reader.ReaderBlock
import com.hanreader.app.reader.ZhEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Readings of standalone polyphones in context; sentences written for the test. */
class PolyphonesTest {
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0)

    /** Readings of every standalone [ch] in [text], in order. */
    private fun readings(text: String, ch: String): List<String> {
        val block = ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine).blocks.first() as ReaderBlock.Text
        return block.tokens.filter { it.text == ch }.map { it.pinyin }
    }

    @Test
    fun deParticles() {
        assertEquals(listOf("de"), readings("他慢慢地走过来。", "地"))
        assertEquals(listOf("de"), readings("她不耐烦地问。", "地"))
        assertEquals(listOf("dì"), readings("魁地奇比赛开始了。", "地"))
        assertEquals(listOf("de"), readings("他说得很快。", "得"))
        assertEquals(listOf("děi"), readings("我得走了。", "得"))
    }

    @Test
    fun changZhang() {
        assertEquals(listOf("cháng"), readings("她的头发很长。", "长"))
        assertEquals(listOf("zhǎng"), readings("他长得很高。", "长"))
    }

    @Test
    fun measureZhi() {
        assertEquals(listOf("zhī"), readings("那只猫在睡觉。", "只"))
    }

    @Test
    fun inWordsTheDictionaryDecides() {
        val block = ChapterBuilder.build(TxtBook("这个地方很好，我得到了一本书。".toByteArray(), "x"), 0, engine).blocks.first() as ReaderBlock.Text
        assertEquals("dìfāng", block.tokens.first { it.text == "地方" }.pinyin.lowercase())
        assertEquals("dédào", block.tokens.first { it.text == "得到" }.pinyin.lowercase())
    }
}
