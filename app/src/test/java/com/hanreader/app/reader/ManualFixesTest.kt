package com.hanreader.app.reader

import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Meanings checked by hand from real books (data/zh/manual.tsv, tools/apply_zh_manual.py) and the
 * general rules found with them. Sentences are own examples in the same constructions.
 */
class ManualFixesTest {
    private fun lines(f: String) = File("src/main/assets/zh/$f").bufferedReader().readLines().asSequence()
    private val engine = ZhEngine(
        ZhSegmenterTest.dict, 0,
        russianAi = ZhEngine.parseRussian(lines("ru_ai.tsv")),
        russianChars = ZhEngine.parseRussianChars(lines("ru_chars.tsv"))
    )

    private fun words(s: String) = engine.segmenter.segment(s).filter { it.kind == TokenKind.HAN }.map { it.text }

    @Test
    fun manualFileIsApplied() {
        val manual = File("../data/zh/manual.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split("\t") }
        for (p in manual) {
            val senses = engine.russianSenses(p[0], p[1].takeIf { p[0].length == 1 })
            assertEquals(p[0], p[2].split(";").first().trim(), senses.firstOrNull())
            if (p[0].length > 1) assertTrue(p[0], ZhSegmenterTest.dict.lookup(p[0])?.hasDefinition == true)
        }
    }

    @Test
    fun wordsAreKeptWhole() {
        assertTrue(words("她冲着窗外喊了一声。").contains("冲着"))
        assertTrue(words("我听见一阵尖叫声。").contains("尖叫声"))
        assertTrue(words("东西都放在储物间里。").contains("储物间"))
        assertTrue(words("希望一切顺顺当当。").contains("顺顺当当"))
    }

    @Test
    fun prepositionSensesComeFirst() {
        assertEquals("к", engine.russianSenses("朝", "cháo").first())
        assertEquals("к", engine.russianSenses("冲", "chòng").first())
    }

    @Test
    fun doubledWordsTakeTheirBaseMeaning() {
        assertEquals(listOf("研究"), engine.compose("研究研究")?.map { it.word })
    }

    @Test
    fun composedWordsStartWithTheLongestWord() {
        // A word without a meaning of its own: 欢呼 + 声, not 欢 + 呼声.
        assertEquals(listOf("欢呼", "声"), engine.compose("欢呼声")?.map { it.word })
    }

    @Test
    fun surnameWithAProperNameIsAName() {
        val seg = engine.segmenter.segment("程卫东笑了。")
        val c = ChineseNames.candidates(seg, { it.text }, { it.kind == TokenKind.HAN },
            { ZhSegmenterTest.dict.lookup(it.text)?.pinyin?.firstOrNull()?.isUpperCase() == true })
        assertTrue(c.toString(), c.any { it.second == "程卫东" })
    }
}
