package com.hanreader.app.reader

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The end-of-chapter review: words and set expressions that had a hint, the tapped and the faded ones first. Own text. */
class ChapterSummaryTest {
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0)

    private val text = "猫头鹰来了。又一只猫头鹰来了。第三只猫头鹰也来了。还有一只猫头鹰。" +
        "房子很远。他画蛇添足。狐狸在睡觉。"

    @Test
    fun hintedWordsAndExpressionsComeBack() {
        val chapter = ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine)
        val unknown = setOf("猫头鹰", "狐狸", "房子", "画蛇添足")
        val glossFor = HashMap<String, String>()
        chapter.blocks.filterIsInstance<ReaderBlock.Text>().forEach { b ->
            b.tokens.filter { it.kind == TokenKind.HAN && it.text in unknown }.forEach { t -> glossFor[t.text] = engine.glossShort(t.text) ?: "?" }
        }
        val items = ChapterSummary.items(chapter, engine, glossFor, repeats = 3,
            isKnown = { _, key -> key == "房子" }, skip = { _, _ -> false }, tapped = setOf("狐狸"))
        val words = items.filter { !it.phrase }.map { it.key }
        // Tapped first, then the word whose hint faded (four times, hints stop after three).
        assertEquals(listOf("狐狸", "猫头鹰"), words)
        assertTrue(items.first { it.key == "猫头鹰" }.faded)
        assertTrue(items.first { it.key == "狐狸" }.tapped)
        // A chengyu is listed as an expression.
        assertTrue(items.first { it.key == "画蛇添足" }.phrase)
        // A word marked known and words without a hint stay out.
        assertFalse(items.any { it.key == "房子" || it.key == "睡觉" })
    }
}
