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
        val text = "他将来北京看窗口。"
        val before = words(text)
        assertTrue("the dictionary glues it: $before", "将来" in before)
        val after = words(text, setOf("将来"))
        assertTrue(after.toString(), "将来" !in after && "将" in after && "来" in after)
        assertTrue("other words stay whole: $after", "窗口" in after)
        // The same characters elsewhere are not affected by other splits.
        assertEquals(before, words(text, setOf("窗户")))
    }
}
