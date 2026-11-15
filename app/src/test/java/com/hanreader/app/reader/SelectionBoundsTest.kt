package com.hanreader.app.reader

import com.hanreader.app.es.EsEngineTest
import com.hanreader.app.zh.ZhContextRegressionTest
import com.hanreader.app.lang.TextOptions
import org.junit.Assert.*
import org.junit.Test

class SelectionBoundsTest {
    private val es by lazy { EsEngineTest.load() }
    @Test fun spanishWordsGrowAndShrinkThroughSpaces() {
        val text="Una casa\u00a0  muy grande."
        val t=es.tokenize(text)
        val casa=t.indexOfFirst { it.text=="casa" }; val muy=t.indexOfFirst { it.text=="muy" }; val grande=t.indexOfFirst { it.text=="grande" }
        assertEquals(muy,SelectionBounds.neighbour(t,casa,1))
        assertEquals(casa,SelectionBounds.neighbour(t,muy,-1))
        assertEquals(muy..grande,SelectionBounds.shrink(t,casa..grande,true))
        assertEquals(casa..muy,SelectionBounds.shrink(t,casa..grande,false))
        assertNull(SelectionBounds.shrink(t,casa..casa,false))
    }
    @Test fun punctuationAndNewlinesStopExpansion() {
        for (text in listOf("casa, grande","casa\ngrande","casa\r\ngrande","casa — grande")) {
            val t=es.tokenize(text);val a=t.indexOfFirst { it.text=="casa" };val b=t.indexOfFirst { it.text=="grande" }
            assertNull(text,SelectionBounds.neighbour(t,a,1))
            assertNull(text,SelectionBounds.neighbour(t,b,-1))
        }
    }
    @Test fun chineseDoesNotRequireSpaces() {
        val text="他走进银行。"
        val t=ZhContextRegressionTest.engine.chapter(listOf(text),TextOptions())(text).tokens
        val a=t.indexOfFirst { it.text=="他" }
        assertEquals(a+1,SelectionBounds.neighbour(t,a,1))
        assertNull(SelectionBounds.neighbour(t,a,-1))
    }
    @Test fun wholePhraseCardKeepsHeadContextAndNeverLeaksToAnotherSelection() {
        val text="La policía investiga el blanqueo de dinero."
        val e=es;val t=e.chapter(listOf(text),TextOptions())(text).tokens
        val a=t.indexOfFirst { it.text=="blanqueo" }; val b=t.indexOfFirst { it.text=="dinero" }
        val head=e.cardAnalyses(t,text,a,a).first().entry
        val phrase=e.cardAnalyses(t,text,a,b).first().entry
        assertNotNull(head.contextMeaning)
        assertEquals(head.contextMeaning,phrase.contextMeaning)
        assertEquals(e.senses(head).first(),e.senses(phrase).first())
        assertNull(e.cardAnalyses(t,text,b,b).first().entry.contextMeaning)
        assertNull(e.entry(phrase.lemma)!!.contextMeaning)
    }
    @Test fun adjacentContextRulesMatchInflectedNeighbours() {
        val text="Las plazas centrales están llenas."
        val t=es.chapter(listOf(text),TextOptions())(text).tokens
        val i=t.indexOfFirst { it.text=="plazas" }
        assertTrue(t[i].pinyin,t[i].pinyin.endsWith("#площадь"))
    }
    @Test fun regularPaymentsAreNotQuotasAndExtortionKeepsPriority() {
        for ((text,word) in listOf("La cuota mensual aumentó." to "cuota", "Las cuotas mensuales aumentaron." to "cuotas",
                "La cuota anual vence mañana." to "cuota", "Las cuotas sindicales son obligatorias." to "cuotas")) {
            val t=es.chapter(listOf(text),TextOptions())(text).tokens;val i=t.indexOfFirst { it.text==word }
            assertTrue(text,es.senses(es.cardAnalyses(t,text,i,i).first().entry).first().contains("взнос"))
        }
        for ((text,word) in listOf("El cártel exige una cuota mensual bajo amenaza." to "cuota", "La extorsión incluye cuotas semanales." to "cuotas")) {
            val t=es.chapter(listOf(text),TextOptions())(text).tokens;val i=t.indexOfFirst { it.text==word }
            assertTrue(text,es.senses(es.cardAnalyses(t,text,i,i).first().entry).first().contains("побор"))
        }
    }

}
