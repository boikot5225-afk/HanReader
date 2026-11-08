package com.hanreader.app.lang

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.reader.ChapterBuilder
import com.hanreader.app.reader.RToken
import com.hanreader.app.reader.ReaderBlock
import com.hanreader.app.zh.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The socket: the chapter builder works with any language module, not only Chinese. */
class LanguageModuleTest {
    /** A minimal module for a language written with spaces: words are runs of letters. */
    private object Spaced : LanguageModule {
        override val info = LanguageInfo("es-ES", "Испанский", null, null, ".!?…\n", "Нет испанского голоса")
        private val word = Regex("\\p{L}+")

        override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText = { text ->
            val tokens = word.findAll(text).map {
                RToken(it.value, glossShort(it.value.lowercase()).orEmpty(), TokenKind.HAN, it.range.first, it.range.last + 1, true)
            }.toList()
            PreparedText(text, text, tokens)
        }

        override fun glossShort(word: String, reading: String?): String? = mapOf("hola" to "привет", "mundo" to "мир")[word]
        override fun cardTranslation(word: String, reading: String?): String? = glossShort(word, reading)
    }

    @Test
    fun chapterBuilderUsesTheModule() {
        val chapter = ChapterBuilder.build(TxtBook("Hola mundo. ¿Qué tal?".toByteArray(), "x"), 0, Spaced)
        val block = chapter.blocks.filterIsInstance<ReaderBlock.Text>().first()
        assertEquals(listOf("Hola", "mundo", "Qué", "tal"), block.tokens.map { it.text })
        assertEquals("привет", block.tokens[0].pinyin)
        assertEquals("mundo", block.text.substring(block.tokens[1].start, block.tokens[1].end))
        assertNull(Spaced.glossShort("tal"))
    }

    @Test
    fun chineseIsTheCurrentLanguage() {
        assertEquals("zh-CN", Languages.current.tag)
        assertEquals("zh-CN", com.hanreader.app.reader.STUDY_LANGUAGE)
    }
}
