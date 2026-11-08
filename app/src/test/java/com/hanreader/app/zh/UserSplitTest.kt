package com.hanreader.app.zh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A word the reader split is never kept whole; other words are untouched. Own sample sentence. */
class UserSplitTest {
    private fun words(text: String, blocked: Set<String> = emptySet()) =
        ZhSegmenterTest.segmenter.segment(text, blocked = blocked).filter { it.kind == TokenKind.HAN }.map { it.text }

    @Test
    fun splitWord() {
        val text = "她把头转向窗口。"
        val before = words(text)
        assertTrue("the dictionary glues it: $before", "把头" in before)
        val after = words(text, setOf("把头"))
        assertTrue(after.toString(), "把头" !in after && "把" in after && "头" in after)
        assertTrue("other words stay whole: $after", "窗口" in after)
        // The same characters elsewhere are not affected by other splits.
        assertEquals(before, words(text, setOf("窗户")))
    }
}
