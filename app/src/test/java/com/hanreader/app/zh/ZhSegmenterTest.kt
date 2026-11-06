package com.hanreader.app.zh

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ZhSegmenterTest {
    companion object {
        // Unit tests run with the module directory as the working directory.
        val dict: ZhDictionary by lazy {
            File("src/main/assets/zh/lexicon.dat").inputStream().use { ZhDictionary.load(it) }
        }
        val segmenter by lazy { ZhSegmenter(dict) }
    }

    private fun words(text: String) =
        segmenter.segment(text).filter { it.kind == TokenKind.HAN }.map { it.text }

    @Test
    fun dictionaryLoadsAllEntries() {
        assertTrue(dict.size > 120_000)
        val e = dict.lookup("中国")
        assertNotNull(e)
        assertEquals("Zhōng guó", e!!.pinyin)
    }

    @Test
    fun tokensCoverTheWholeText() {
        val text = "他说：“我明天去北京，OK？” 二〇二六年。"
        val tokens = segmenter.segment(text)
        assertEquals(text, tokens.joinToString("") { it.text })
        tokens.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
    }

    @Test
    fun commonSentences() {
        assertEquals(listOf("我", "喜欢", "看书"), words("我喜欢看书"))
        assertEquals(listOf("他", "终于", "回来", "了"), words("他终于回来了"))
    }

    /** Polyphonic characters use their common reading, phrases their contextual one. */
    @Test
    fun commonReadings() {
        val expected = mapOf(
            "离" to "lí", "还" to "hái", "都" to "dōu", "只" to "zhǐ", "要" to "yào",
            "银行" to "yín háng", "长大" to "zhǎng dà", "觉得" to "jué de", "便宜" to "pián yi", "中国" to "Zhōng guó"
        )
        expected.forEach { (word, reading) -> assertEquals(word, reading, dict.lookup(word)!!.pinyin) }
    }

    /** Regression cases carried over from Reader AI (data/zh_lexical_regressions.json). */
    @Test
    fun readerAiRegressions() {
        val cases = JSONArray(javaClass.classLoader!!.getResource("zh_lexical_regressions.json")!!.readText())
        val failures = mutableListOf<String>()
        val details = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = words(c.getString("text")).toSet()
            val need = buildList {
                for (key in listOf("must", "mustContain")) {
                    val arr = c.optJSONArray(key) ?: continue
                    for (k in 0 until arr.length()) add(arr.getString(k))
                }
            }
            val forbid = c.optJSONArray("forbid")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val missing = need.filter { it !in got }
            val bad = forbid.filter { it in got }
            if (missing.isNotEmpty() || bad.isNotEmpty()) {
                failures += c.getString("id")
                details += "${c.getString("id")}: missing=$missing forbidden=$bad got=${words(c.getString("text"))}"
            }
        }
        println("Reader AI regressions: ${cases.length() - failures.size}/${cases.length()} pass")
        details.forEach { println("  FAIL $it") }
        val unexpected = failures - KNOWN_FAILURES
        assertTrue("New regressions:\n" + details.joinToString("\n"), unexpected.isEmpty())
    }

    /**
     * Known differences from Reader AI, which used a larger proprietary-source lexicon:
     * 副主席 / 所需 are split by Jieba frequencies, 张又侠 is a name absent from every open lexicon.
     */
    private val KNOWN_FAILURES = setOf("central-military-commission", "memory-market", "proper-name-surname")
}
