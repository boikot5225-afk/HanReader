package com.hanreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardImportParserTest {
    @Test fun validRowsWithBlankReading() {
        val value = CardImportParser.parse(
            "终于 | zhōngyú | наконец | 他终于来了。 | Он наконец пришёл.\n" +
            "\nbonjour | | здравствуйте | Bonjour à tous ! | Всем привет!"
        )
        assertEquals(2, value.cards.size)
        assertEquals(0, value.problems.size)
        assertEquals(3, value.cards[1].line)
        assertEquals("", value.cards[1].reading)
    }

    @Test fun mixedRowsPreserveCorrectEntries() {
        val value = CardImportParser.parse(
            "bad line\n" +
            "考える | | думать | よく考えてください。 | Хорошо подумайте.\n" +
            "test | ipa | value | | translation"
        )
        assertEquals(1, value.cards.size)
        assertEquals(2, value.problems.size)
        assertEquals(1, value.problems[0].line)
        assertEquals(3, value.problems[1].line)
    }

    @Test fun blankInputProducesEmptyPreview() {
        val value = CardImportParser.parse("\n \n")
        assertTrue(value.cards.isEmpty())
        assertTrue(value.problems.isEmpty())
    }
}
