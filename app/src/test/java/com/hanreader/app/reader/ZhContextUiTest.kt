package com.hanreader.app.reader

import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhContextRegressionTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ZhContextUiTest {
    @Test fun hintsUseDifferentKeysForDifferentSenses() {
        val a=RToken("妖精","yāojing",TokenKind.HAN,0,2,true,contextMeaning="гоблин")
        val b=a.copy(contextMeaning=null)
        val map=mapOf(a.glossKey to "гоблин",b.glossKey to "нечистая сила")
        assertEquals("гоблин",glossOf(map,a,0))
        assertEquals("нечистая сила",glossOf(map,b,0))
    }
    @Test fun userBookAndPlaceMeaningsOverrideContext() {
        val e=ZhContextRegressionTest.engine
        val t=RToken("妖精","yāojing",TokenKind.HAN,0,2,true,contextMeaning="гоблин")
        val chapter=ChapterContent(0,"Пример",listOf(ReaderBlock.Text("妖精",false,listOf(t))),0,2)
        val map=mutableMapOf(t.glossKey to "гоблин")
        ContextMeanings.addTo(map,chapter,emptyMap(),mapOf("妖精" to "мой перевод книги"),e,setOf("妖精"))
        assertEquals("мой перевод книги",glossOf(map,t,0))
        ContextMeanings.addTo(map,chapter,mapOf("0:0" to "мой перевод места"),emptyMap(),e,setOf("妖精"))
        assertEquals("мой перевод места",glossOf(map,t,0))
    }
}
