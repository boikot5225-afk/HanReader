package com.hanreader.app.zh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZhNumbersTest {
    @Test
    fun yearsDatesAndUnits() {
        assertEquals("1931 год", ZhNumbers.gloss("一九三一年"))
        assertEquals("1911 год", ZhNumbers.gloss("一九一一年"))
        assertEquals("14-е число", ZhNumbers.gloss("十四日"))
        assertEquals("глава 9", ZhNumbers.gloss("第九章"))
        assertEquals("глава 4", ZhNumbers.gloss("第四回"))
        assertEquals("4 ляна", ZhNumbers.gloss("四两"))
        assertEquals("5–6 лет", ZhNumbers.gloss("五六年"))
        assertEquals("2–3", ZhNumbers.gloss("两三"))
        assertEquals("150", ZhNumbers.gloss("一百五十"))
        assertEquals("20000", ZhNumbers.gloss("二十千"))
        assertEquals("20-й", ZhNumbers.gloss("第二十"))
        assertEquals("92", ZhNumbers.gloss("九十二"))
    }

    @Test
    fun notNumbers() {
        assertNull(ZhNumbers.gloss("一"))
        assertNull(ZhNumbers.gloss("两"))
        assertNull(ZhNumbers.gloss("半寸"))
        assertNull(ZhNumbers.gloss("一定"))
    }
}
