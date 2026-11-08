package com.hanreader.app.zh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grammar constructions found by their markers; sentences written for the test. */
class GrammarTest {
    private fun tokens(text: String) = ZhSegmenterTest.segmenter.segment(text).map { it.text }
    private fun titles(text: String) = Grammar.find(tokens(text)).map { it.point.title.substringBefore(" ") }

    /** Every construction is found in its own example sentence. */
    @Test
    fun examplesShowTheirConstruction() {
        val failures = Grammar.POINTS.filter { p -> Grammar.find(tokens(p.example)).none { it.point === p } }
            .map { "${it.title}: ${it.example} [${tokens(it.example).joinToString("|")}]" }
        assertTrue(failures.joinToString("\n", "\n"), failures.isEmpty())
    }

    @Test
    fun baIsNotTheMeasureWord() {
        assertTrue("把" in titles("我把书放在桌子上。"))
        assertTrue("把" !in titles("他拿着一把刀。"))
        assertTrue("把" !in titles("这把椅子很旧。"))
    }

    @Test
    fun partsInOrderAndNear() {
        assertTrue("虽然" in titles("虽然天很冷，可是我们还是出去了。"))
        assertTrue("因为" !in titles("所以我说，因为你。"))
        assertTrue("一" !in titles("一个人就够了。"))
        assertTrue("一" in titles("我一看就明白了。"))
    }

    @Test
    fun atTheMarker() {
        val t = tokens("虽然很累，但是他很高兴。")
        val dan = t.indexOf("但是")
        assertEquals(listOf("虽然"), Grammar.at(t, dan).map { it.point.title.substringBefore(" ") })
        assertTrue(Grammar.at(t, t.indexOf("高兴")).isEmpty())
    }
}
