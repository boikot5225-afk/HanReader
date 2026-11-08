package com.hanreader.app.reader

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Short translations over words: no labels, fitting combinations, fading after a few appearances. Own sample text. */
class WordHintsTest {
    private val engine = ZhEngine(
        ZhSegmenterTest.dict, 0,
        russian = ZhEngine.parseRussian(sequenceOf(
            "猫头鹰\tорнитол. сова ; филин",
            "流星雨\tастрон. метеорный поток",
            "不定\tнеопределённый ; неустойчивый ; неясный"
        ))
    )

    @Test
    fun labelsAreDropped() {
        assertEquals("сова", engine.glossShort("猫头鹰"))
        assertEquals("метеорный поток", engine.glossShort("流星雨"))
    }

    @Test
    fun combinationPartsAreShort() {
        val g = engine.composedGloss("心不定") ?: return // only when the dictionary composes it
        assertTrue(g, g.endsWith("неясный"))
    }

    @Test
    fun translationsFade() {
        val text = "我喜欢猫头鹰。猫头鹰很好看。他说猫头鹰会飞。猫头鹰晚上出来。"
        val chapter = ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine)
        val owl = mapOf("猫头鹰" to "сова")
        val keep = fadingGlosses(chapter, owl, 3)
        val blocks = chapter.blocks.withIndex().filter { it.value is ReaderBlock.Text }
        val owls = blocks.flatMap { (bi, b) -> (b as ReaderBlock.Text).tokens.withIndex().filter { it.value.text == "猫头鹰" }.map { bi to it.index } }
        assertEquals(4, owls.size)
        val shown = owls.filter { (bi, ti) -> ti in keep[bi].orEmpty() }
        assertEquals("the first three keep the translation", owls.take(3), shown)
    }
}
