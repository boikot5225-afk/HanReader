package com.hanreader.app.lang

import com.hanreader.app.epub.TxtBook
import org.junit.Assert.assertEquals
import org.junit.Test

class BookLanguagesTest {
    @Test
    fun languageFromTheText() {
        assertEquals(Languages.CHINESE, BookLanguages.recognize(TxtBook("第一章 雨夜\n那天晚上一直在下雨。".toByteArray(), "x")))
        assertEquals(Languages.SPANISH, BookLanguages.recognize(TxtBook("Capítulo 1\nLa lluvia había empezado poco después de medianoche.".toByteArray(), "x")))
        // A Chinese book with an English name in it is still Chinese.
        assertEquals(Languages.CHINESE, BookLanguages.recognize(TxtBook("他在 New York 住了三年，每天都去公园散步。".toByteArray(), "x")))
    }

    /** Amauta Reader reads Spanish and Chinese and opens in Spanish; a new book is read in the language of its text. */
    @Test
    fun anAppReadsItsOwnLanguages() {
        assertEquals(listOf(Languages.SPANISH, Languages.CHINESE), Languages.AVAILABLE)
        assertEquals(Languages.SPANISH, Languages.default)
        val was = Languages.current
        Languages.current = Languages.SPANISH
        try {
            assertEquals(Languages.CHINESE, BookLanguages.detect(TxtBook("第一章 雨夜\n那天晚上一直在下雨。".toByteArray(), "x")))
        } finally {
            Languages.current = was
        }
    }

    @Test
    fun eachLanguageOwnsItsWords() {
        val zh = Languages.CHINESE.ownsWord
        val es = Languages.SPANISH.ownsWord
        assertEquals(true, zh("你好"))
        assertEquals(false, zh("hablar"))
        assertEquals(true, es("canción"))
        assertEquals(true, es("darse cuenta"))
        assertEquals(false, es("你好"))
        assertEquals(false, es("1990"))
    }

    @Test
    fun daysAreKeptPerLanguage() {
        val rows = listOf(com.hanreader.app.DailyActivity(20261109, readMillis = 1), com.hanreader.app.DailyActivity(1_020_261_109, readMillis = 2))
        assertEquals(listOf(20261109), LanguageDays.own(rows).map { it.day })
        assertEquals(20261109, LanguageDays.key(20261109))
        val was = Languages.current
        Languages.current = Languages.SPANISH
        try {
            assertEquals(1_020_261_109, LanguageDays.key(20261109))
            assertEquals(listOf(20261109 to 2L), LanguageDays.own(rows).map { it.day to it.readMillis })
        } finally { Languages.current = was }
    }
}
