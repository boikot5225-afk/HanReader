package com.hanreader.app.lang

import com.hanreader.app.epub.TxtBook
import org.junit.Assert.assertEquals
import org.junit.Test

class BookLanguagesTest {
    @Test
    fun languageFromTheText() {
        assertEquals(Languages.CHINESE, BookLanguages.detect(TxtBook("第一章 雨夜\n那天晚上一直在下雨。".toByteArray(), "x")))
        assertEquals(Languages.SPANISH, BookLanguages.detect(TxtBook("Capítulo 1\nLa lluvia había empezado poco después de medianoche.".toByteArray(), "x")))
        // A Chinese book with an English name in it is still Chinese.
        assertEquals(Languages.CHINESE, BookLanguages.detect(TxtBook("他在 New York 住了三年，每天都去公园散步。".toByteArray(), "x")))
    }
}
