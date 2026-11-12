package com.hanreader.app.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** The message with the doubted translations: what stood in the book, what the app showed, where. */
class DoubtsTest {
    @Test
    fun oneMessageForAll() {
        val text = Doubts.message("Amauta Reader", listOf(
            Doubts.Doubt("dirigida", "dirigido", "управляемый", "La mujer había escrito una carta dirigida a Ramón.", "Libro · 5. Capítulo", 0),
            Doubts.Doubt("casa", "casa", "", "", "", 0)
        ))
        assertEquals(
            "Amauta Reader: переводы под вопросом (2)\n" +
                "\n1. dirigida → dirigido — «управляемый»\n" +
                "   La mujer había escrito una carta dirigida a Ramón.\n" +
                "   Libro · 5. Capítulo\n" +
                "\n2. casa — «нет значения»\n",
            text
        )
    }
}
