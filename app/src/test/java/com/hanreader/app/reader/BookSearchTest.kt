package com.hanreader.app.reader

import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Own sample text. */
class BookSearchTest {
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0)
    private val text = "雨水顺着他的帽子流下来。他顺着街走，终于到了书店。"

    @Test
    fun tonelessPinyin() {
        assertEquals("zhongguo", BookSearch.toneless("Zhōng guó"))
        assertEquals("lvxing", BookSearch.toneless("lǚ xíng"))
        assertTrue(BookSearch.isPinyinQuery("shun zhe"))
        assertFalse(BookSearch.isPinyinQuery("顺着"))
    }

    @Test
    fun textMatchesEveryOccurrence() {
        val r = BookSearch.textMatches(text, "顺着")
        assertEquals(2, r.size)
        assertEquals("顺着", text.substring(r[0].first, r[0].last + 1))
    }

    @Test
    fun pinyinMatchesSyllablesInContext() {
        val r = BookSearch.pinyinMatches(text, BookSearch.toneless("shùnzhe"), engine)
        assertEquals(2, r.size)
        r.forEach { assertEquals("顺着", text.substring(it.first, it.last + 1)) }
        // Two words in a row also match: "zhongyu" (终于).
        val z = BookSearch.pinyinMatches(text, "zhongyu", engine)
        assertEquals(listOf("终于"), z.map { text.substring(it.first, it.last + 1) })
        // Across punctuation no match is made.
        assertTrue(BookSearch.pinyinMatches(text, "laita", engine).isEmpty())
        // Inside a longer word: 电话 in 打电话; polyphones read in context: 银行 is yinhang, not yinxing.
        val t2 = "他给银行打电话。"
        assertEquals(listOf("电话"), BookSearch.pinyinMatches(t2, "dianhua", engine).map { t2.substring(it.first, it.last + 1) })
        assertEquals(listOf("银行"), BookSearch.pinyinMatches(t2, "yinhang", engine).map { t2.substring(it.first, it.last + 1) })
        assertTrue(BookSearch.pinyinMatches(t2, "yinxing", engine).isEmpty())
    }
}
