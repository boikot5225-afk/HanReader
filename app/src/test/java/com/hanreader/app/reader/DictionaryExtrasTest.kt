package com.hanreader.app.reader

import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Measure words of nouns and the labels of set expressions in the word sheet. */
class DictionaryExtrasTest {
    private val measures = File("src/main/assets/zh/measure.tsv").bufferedReader().useLines { ZhEngine.parseMeasures(it) }
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0, measures = measures)

    @Test
    fun nounsHaveTheirMeasureWordsWithReadings() {
        assertTrue(measures.size > 3000)
        assertEquals("张 zhāng", engine.measureWords("椅子").first())
        assertTrue(engine.measureWords("信").contains("封 fēng"))
        assertTrue(engine.measureWords("跑").isEmpty())
    }

    @Test
    fun idiomsShowTheFigurativeSenseAndTheLiteralOne() {
        val ru = File("src/main/assets/zh/ru.tsv").bufferedReader().useLines { ZhEngine.parseRussian(it) }
        val ruAi = File("src/main/assets/zh/ru_ai.tsv").bufferedReader().useLines { ZhEngine.parseRussian(it) }
        val e = ZhEngine(ZhSegmenterTest.dict, 0, russian = ru, russianAi = ruAi)
        // 画蛇添足 is in the Russian Wiktionary too («перестараться»); the idiom form wins.
        assertTrue("画蛇添足" in ru)
        val senses = e.russianSenses("画蛇添足")
        assertTrue(senses.any { it.startsWith("букв.") })
        assertEquals(ZhEngine.RussianSource.AI, e.russianSource("画蛇添足"))
        // Ordinary words keep the Wiktionary first.
        assertEquals(ZhEngine.RussianSource.WIKTIONARY, e.russianSource("事实"))
        // Names and terms now have Russian too.
        assertTrue(e.russianSenses("长城").first().contains("Великая Китайская стена"))
    }

    @Test
    fun setExpressionsAreLabelled() {
        assertTrue(idiomKind(ZhSegmenterTest.dict.lookup("画蛇添足")?.gloss)!!.startsWith("成语"))
        assertTrue(idiomKind(ZhSegmenterTest.dict.lookup("一方水土养一方人")?.gloss)!!.startsWith("谚语"))
        assertNull(idiomKind(ZhSegmenterTest.dict.lookup("图书馆")?.gloss))
        assertNull(idiomKind(null))
    }
}
