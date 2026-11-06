package com.hanreader.app.epub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Sample books written for this test. */
class BookFormatsTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    private fun temp(name: String, bytes: ByteArray) = File.createTempFile("hanreader", name).apply { writeBytes(bytes); deleteOnExit() }

    private val txt = """
        序章
        那天晚上一直在下雨。
        第一章　雨夜
        他站在门口，等一个不会来的人。
        门开了。
        第二章 早上
        天亮了，雨也停了。
    """.trimIndent()

    @Test
    fun txtChaptersFromHeadings() {
        val book = TxtBook(txt.toByteArray(), "雨夜")
        assertEquals("雨夜", book.title)
        assertEquals(listOf("序章", "第一章　雨夜", "第二章 早上"), book.chapters.map { it.title })
        val blocks = book.blocks(1)
        assertEquals(EpubBlock.Text("第一章　雨夜", heading = true), blocks[0])
        assertEquals(listOf("他站在门口，等一个不会来的人。", "门开了。"), blocks.drop(1).map { (it as EpubBlock.Text).text })
    }

    @Test
    fun txtEncodings() {
        val gb = TxtBook(txt.toByteArray(Charset.forName("GB18030")), "x")
        assertEquals("第二章 早上", gb.chapters[2].title)
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + txt.toByteArray(Charsets.UTF_16LE)
        assertEquals(3, TxtBook(utf16, "x").chapters.size)
        assertEquals(BookFormat.TXT, BookFiles.sniff(temp(".txt", utf16)))
    }

    @Test
    fun txtWithoutHeadingsIsSplitIntoParts() {
        val long = (1..2000).joinToString("\n") { "这是第${it}行，他慢慢地往前走。" }
        val book = TxtBook(long.toByteArray(), "长")
        assertTrue(book.chapters.size > 3)
        assertTrue(book.chapters.all { it.title.startsWith("Часть") })
        assertEquals(2000, book.chapters.indices.sumOf { book.blocks(it).size })
    }

    private val fb2 = """
        <?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description><title-info>
            <author><first-name>小</first-name><last-name>明</last-name></author>
            <book-title>雨夜的灯</book-title>
            <coverpage><image l:href="#cover.png"/></coverpage>
          </title-info></description>
          <body>
            <section><title><p>第一章</p></title><p>那天晚上一直在下雨。</p><image l:href="#cover.png"/></section>
            <section><title><p>第二部</p></title>
              <section><title><p>第二章</p></title><p>他笑了笑。<a l:href="#n1" type="note">1</a></p></section>
              <section><title><p>第三章</p></title><p>天亮了。</p></section>
            </section>
          </body>
          <body name="notes"><section id="n1"><p>注释</p></section></body>
          <binary id="cover.png" content-type="image/png">${Base64.getEncoder().encodeToString(png)}</binary>
        </FictionBook>
    """.trimIndent()

    @Test
    fun fb2MetadataSectionsImagesCover() {
        val file = temp(".fb2", fb2.toByteArray())
        assertEquals(BookFormat.FB2, BookFiles.sniff(file))
        BookFiles.open(file, BookFormat.FB2).use { book ->
            assertEquals("雨夜的灯", book.title)
            assertEquals("小 明", book.author)
            // The part holding only sub-sections is split into them; the notes body is not a chapter.
            assertEquals(listOf("第一章", "第二章", "第三章"), book.chapters.map { it.title })
            assertEquals(
                listOf(EpubBlock.Text("第一章", true), EpubBlock.Text("那天晚上一直在下雨。", false), EpubBlock.Image("cover.png")),
                book.blocks(0)
            )
            assertEquals(EpubBlock.Text("他笑了笑。", false), book.blocks(1)[1]) // note reference dropped
            assertArrayEquals(png, book.bytes("cover.png"))
            assertArrayEquals(png, book.cover())
        }
    }

    @Test
    fun zippedFb2() {
        val file = File.createTempFile("hanreader", ".fb2.zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("book.fb2")); z.write(fb2.toByteArray()); z.closeEntry()
        }
        assertEquals(BookFormat.FB2_ZIP, BookFiles.sniff(file))
        BookFiles.open(file, BookFormat.FB2_ZIP).use { assertEquals(3, it.chapters.size) }
    }

    @Test
    fun epubCoverFromMetadata() {
        val file = File.createTempFile("hanreader", ".epub").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/c.opf"/></rootfiles></container>""".toByteArray())
            put("OPS/c.opf", """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>书</dc:title>
                <meta name="cover" content="img"/></metadata><manifest>
                <item id="img" href="i/cover.png" media-type="image/png"/>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/></spine></package>""".toByteArray())
            put("OPS/a.xhtml", "<html><body><p>天亮了。</p></body></html>".toByteArray())
            put("OPS/i/cover.png", png)
        }
        assertEquals(BookFormat.EPUB, BookFiles.sniff(file))
        BookFiles.open(file, BookFormat.EPUB).use { assertArrayEquals(png, it.cover()) }
    }

    @Test
    fun sniffRejectsBinaryAndOpensByStoredExtension() {
        assertNull(BookFiles.sniff(temp(".bin", byteArrayOf(1, 0, 2, 0, 3))))
        val stored = temp(".fb2", fb2.toByteArray())
        val renamed = File(stored.parentFile, "book-1.fb2").apply { writeBytes(stored.readBytes()); deleteOnExit() }
        BookFiles.open(renamed).use { assertNotNull(it.title) }
    }
}
