package com.hanreader.app.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PastedTextTest {
    @Test
    fun tidy() {
        assertEquals("第一行\n\n第二行\n第三行", PastedText.tidy("\r\n  \n第一行   \r\n\r\n\r\n\r\n第二行 \n第三行\n\n"))
    }

    @Test
    fun title() {
        assertEquals("第一行", PastedText.title("\n第一行\n第二行"))
        assertEquals(30, PastedText.title("很".repeat(50)).length)
        assertEquals("Текст", PastedText.title(""))
    }
}
