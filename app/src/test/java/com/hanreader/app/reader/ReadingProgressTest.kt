package com.hanreader.app.reader

import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Book difficulty, the reading counter and character meanings by reading. */
class ReadingProgressTest {
    @Test
    fun difficultyThresholds() {
        assertEquals(ReadingStats.Difficulty.COMFORTABLE, ReadingStats.Difficulty.of(97))
        assertEquals(ReadingStats.Difficulty.COMFORTABLE, ReadingStats.Difficulty.of(95))
        assertEquals(ReadingStats.Difficulty.WITH_HINTS, ReadingStats.Difficulty.of(92))
        assertEquals(ReadingStats.Difficulty.HARD, ReadingStats.Difficulty.of(85))
        assertEquals(ReadingStats.Difficulty.TOO_HARD, ReadingStats.Difficulty.of(74))
    }

    @Test
    fun counterAndMilestones() {
        assertEquals("940", ReadingStats.amount(940))
        assertEquals("3,8 тыс.", ReadingStats.amount(3_820))
        assertEquals("128 тыс.", ReadingStats.amount(128_400))
        assertEquals("1,2 млн", ReadingStats.amount(1_234_000))
        assertEquals("2 млн", ReadingStats.amount(2_000_000))
        assertEquals(10_000L, ReadingStats.nextMilestone(0))
        assertEquals(250_000L, ReadingStats.nextMilestone(128_400))
        assertEquals(15_000_000L, ReadingStats.nextMilestone(12_000_000))
    }

    @Test
    fun characterMeaningsFollowTheReading() {
        val chars = File("src/main/assets/zh/ru_chars.tsv").bufferedReader().useLines { ZhEngine.parseRussianChars(it) }
        val engine = ZhEngine(ZhSegmenterTest.dict, 0, russianChars = chars)
        // 长 in 长大 is zhǎng «расти», in 很长 cháng «длинный».
        assertTrue(engine.charGloss("长", "zhǎng")!!, "раст" in engine.charGloss("长", "zhǎng")!! || "глав" in engine.charGloss("长", "zhǎng")!!)
        assertTrue(engine.charGloss("长", "cháng")!!.startsWith("длинн"))
        // Up to two short senses, cut when long.
        val g = engine.charGloss("目", "mù")!!
        assertTrue(g, g.split("; ").size <= 2 && g.split("; ").all { it.length <= 14 })
    }
}
