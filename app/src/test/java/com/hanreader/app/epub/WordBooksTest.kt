package com.hanreader.app.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Word files made with LibreOffice from text written for this test (test resources /word). */
class WordBooksTest {
    private fun res(name: String): File {
        val f = File.createTempFile("hanreader", name)
        f.writeBytes(javaClass.classLoader!!.getResource("word/$name")!!.readBytes())
        f.deleteOnExit()
        return f
    }

    @Test
    fun docxHeadingsBecomeChapters() {
        val f = res("sample.docx")
        assertEquals(BookFormat.DOCX, BookFiles.sniff(f))
        BookFiles.open(f, BookFormat.DOCX).use { book ->
            assertEquals(listOf("第一章 雨夜", "第二章 早上"), book.chapters.map { it.title })
            val blocks = book.blocks(0)
            assertEquals(EpubBlock.Text("第一章 雨夜", heading = true), blocks[0])
            assertEquals("那天晚上一直在下雨。街灯很暗，他站在门口，等一个不会来的人。", (blocks[1] as EpubBlock.Text).text)
            assertTrue((book.blocks(1).last() as EpubBlock.Text).text.contains("Latin text 123."))
        }
    }

    @Test
    fun legacyDocText() {
        val f = res("sample.doc")
        assertEquals(BookFormat.DOC, BookFiles.sniff(f))
        BookFiles.open(f, BookFormat.DOC).use { book ->
            // The binary format has no reliable heading styles here: chapters come from the 第…章 lines.
            assertEquals(listOf("第一章 雨夜", "第二章 早上"), book.chapters.map { it.title })
            val texts = book.chapters.indices.flatMap { i -> book.blocks(i).map { (it as EpubBlock.Text).text } }
            assertTrue("那天晚上一直在下雨。街灯很暗，他站在门口，等一个不会来的人。" in texts)
            assertTrue("天亮了，雨也停了。Latin text 123." in texts)
        }
    }

    @Test
    fun longLegacyDocAllParagraphs() {
        val f = res("long.doc")
        BookFiles.open(f, BookFormat.DOC).use { book ->
            assertEquals(3, book.chapters.size)
            val texts = book.chapters.indices.flatMap { i -> book.blocks(i).map { (it as EpubBlock.Text).text } }
            assertEquals(3 + 3 * 120, texts.size)
            assertTrue("这是第3章的第120段，他慢慢地往前走，雨一直没有停。Mixed ASCII 119." in texts)
        }
    }
}
