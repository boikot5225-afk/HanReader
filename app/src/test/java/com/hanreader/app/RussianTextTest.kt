package com.hanreader.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianTextTest {
    @Test fun choosesRussianPluralForms() {
        val forms = listOf(1, 2, 5, 11, 12, 21, 22, 25, 111, 104)
            .map { RussianText.plural(it, "слово", "слова", "слов") }
        assertEquals(
            listOf("слово", "слова", "слов", "слов", "слов", "слово", "слова", "слов", "слов", "слова"),
            forms
        )
    }

    @Test fun formatsDurations() {
        assertEquals("1 мин", RussianText.duration(10_000L))
        assertEquals("10 мин", RussianText.duration(600_000L))
        assertEquals("2 ч", RussianText.duration(2 * 3_600_000L + 5_000L))
        assertEquals("3 дня", RussianText.duration(3 * 86_400_000L))
    }
}
