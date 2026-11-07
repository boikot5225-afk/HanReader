package com.hanreader.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every licence notice the «О приложении» screen opens is in the app. */
class AboutTest {
    @Test
    fun noticesExist() {
        val dir = File("src/main/assets")
        (CREDITS.map { "licenses/${it.file}" } + LICENSE_TEXTS.map { "licenses/${it.second}" } + "privacy_ru.md").forEach {
            assertTrue("missing asset $it", File(dir, it).isFile)
        }
        // Every bundled data set has a credit.
        val credited = CREDITS.map { it.file }.toSet()
        listOf("CC-CEDICT_NOTICE.txt", "RU_WIKTIONARY_NOTICE.txt", "RU_AI_NOTICE.txt", "OPENCC_NOTICE.txt", "FONTS_NOTICE.txt")
            .forEach { assertTrue(it, it in credited) }
    }
}
