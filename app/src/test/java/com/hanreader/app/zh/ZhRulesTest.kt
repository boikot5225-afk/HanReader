package com.hanreader.app.zh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The rule file's language: sets, negation, "-" for the dictionary's reading, cuts. */
class ZhRulesTest {
    private val rules = ZhRules.parse(
        """
        # a comment
        set	NUM	一二三
        read	行	prev:${'$'}NUM & next & !next:了吗	háng	一行字
        read	只	!prev:这那	-
        read	只	*	zhī
        read	看	next^孩 & !next=孩子们	kān
        split	却说	prev	1
        split	回城	prev:${'$'}NUM	9	a cut past the end is no cut
        """.trimIndent().lineSequence()
    )

    private fun tok(text: String) = ZhToken(text, 0, text.length, TokenKind.HAN, null)

    @Test
    fun readings() {
        assertEquals("háng", rules.reading("行", "一", "字"))
        assertNull(rules.reading("行", "一", "了"))
        assertNull(rules.reading("行", "一", null))
        assertEquals("", rules.reading("只", "他", "是"))
        assertEquals("zhī", rules.reading("只", "那", "猫"))
        assertEquals("kān", rules.reading("看", "去", "孩子"))
        assertNull(rules.reading("看", "去", "孩子们"))
        assertNull(rules.reading("走", "去", "了"))
    }

    @Test
    fun cuts() {
        val t = listOf(tok("他"), tok("却说"))
        assertEquals(1, rules.splitAt(t, 1, "他", null))
        assertEquals(0, rules.splitAt(listOf(tok("却说")), 0, null, null))
        assertEquals(0, rules.splitAt(listOf(tok("一"), tok("回城")), 1, "一", null))
        assertNull(rules.splitAt(listOf(tok("今天")), 0, null, null))
    }

    /** Every line of the app's file is understood: a typo would silently drop a rule. */
    @Test
    fun appFileParses() {
        val lines = File("src/main/assets/zh/context.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("set\t") }
        val sets = File("src/main/assets/zh/context.tsv").readLines().filter { it.startsWith("set\t") }.map { it.split('\t')[1] }.toSet()
        val known = setOf("*", "prev", "next", "long-prev")
        val prefixes = listOf("next-any:", "earlier:", "later:", "prev0:", "prev:", "next:", "prev=", "next=", "prev~", "next^")
        for (l in lines) {
            val p = l.split('\t')
            assertEquals("columns: $l", true, p.size >= 4 && p[0] in setOf("read", "split"))
            for (c in p[2].split(" & ")) {
                val bare = c.trim().removePrefix("!")
                assertEquals("condition «$bare» in: $l", true, bare in known || prefixes.any { bare.startsWith(it) && bare.length > it.length })
            }
            for (m in Regex("\\$([A-Z_]+)").findAll(p[2])) assertTrue("unknown set ${m.value} in: $l", m.groupValues[1] in sets)
        }
        val all = ZhRules.load(File("src/main/assets/zh/context.tsv"))
        assertEquals("de", all.reading("地", "慢慢", "走"))
    }
}
