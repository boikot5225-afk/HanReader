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
            ZhDictionary.map(File("src/main/assets/zh/lexicon.bin"))
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

    /** Chengyu and sayings from the English Wiktionary are whole words with a reading per character. */
    @Test
    fun wiktionarySayingsAreWholeWords() {
        assertEquals(listOf("他们", "一决胜负"), words("他们一决胜负"))
        assertEquals(listOf("你", "别", "跟", "他", "一个鼻孔出气"), words("你别跟他一个鼻孔出气"))
        assertEquals(listOf("一方水土养一方人"), words("一方水土养一方人"))
        val e = dict.lookup("一决胜负")!!
        assertTrue(e.gloss.startsWith("(idiom)"))
        assertEquals(4, e.pinyin.split(' ').size)
        // A word the segmenter knew without a meaning now has one.
        assertTrue(dict.lookup("一举成名")!!.hasDefinition)
    }

    /** The binary lexicon holds exactly what the source TSV (data/zh/lexicon.dat) holds. */
    @Test
    fun binaryLexiconMatchesTheSource() {
        var n = 0
        java.util.zip.GZIPInputStream(File("../data/zh/lexicon.dat").inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                val p = line.split('\t')
                if (p.size < 5 || p[0].isEmpty()) continue
                val e = dict.lookup(p[0])
                assertNotNull(p[0], e)
                assertEquals(p[1], e!!.pinyin)
                assertEquals(p[2], e.gloss)
                assertEquals(p[3].toInt(), e.rank)
                assertEquals(p[4].toInt(), e.freq)
                n++
            }
        }
        assertEquals(n, dict.size)
        assertEquals(-1, dict.freq("不是一个词语啊"))
        assertTrue(!dict.contains(""))
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
            "银行" to "yín háng", "长大" to "zhǎng dà", "觉得" to "jué de", "便宜" to "pián yi", "中国" to "Zhōng guó",
            // Single characters: ordinary reading, not the surname one.
            "章" to "zhāng", "德" to "dé"
        )
        expected.forEach { (word, reading) -> assertEquals(word, reading, dict.lookup(word)!!.pinyin) }
    }

    /**
     * The sentence set in src/test/resources/segmentation.tsv: words in context (把门 as 把 + 门
     * before a verb, 才能 as 才 + 能, numerals apart from measure words). Lines starting with "?"
     * are known hard cases: counted, not required.
     */
    @Test
    fun sentenceSet() {
        val lines = javaClass.classLoader!!.getResourceAsStream("segmentation.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size >= 60)
        val failures = ArrayList<String>()
        var hard = 0
        var hardPassed = 0
        for (line in lines) {
            val optional = line.startsWith("?")
            val (sentence, expected) = line.removePrefix("?").split("\t")
            val got = words(sentence).joinToString(" ")
            if (optional) { hard++; if (got == expected) hardPassed++ }
            else if (got != expected) failures += "$sentence\n    expected $expected\n    got      $got"
        }
        println("Segmentation set: ${lines.size - hard - failures.size}/${lines.size - hard} pass, hard cases $hardPassed/$hard")
        assertTrue(failures.joinToString("\n", "\n"), failures.isEmpty())
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
