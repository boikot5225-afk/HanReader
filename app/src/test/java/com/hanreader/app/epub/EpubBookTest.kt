package com.hanreader.app.epub

import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookTest {

    private fun sampleEpub(): File {
        val file = File.createTempFile("sample", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
            )
            put(
                "OEBPS/content.opf",
                """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata>
                <dc:title>测试书</dc:title><dc:creator>作者</dc:creator><dc:language>zh</dc:language></metadata>
                <manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="Text/ch%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
            )
            put(
                "OEBPS/toc.ncx",
                """<ncx><navMap><navPoint><navLabel><text>第一章</text></navLabel><content src="Text/ch%201.xhtml#a"/>
                <navPoint><navLabel><text>第一节</text></navLabel><content src="Text/ch%201.xhtml#b"/></navPoint></navPoint>
                <navPoint><navLabel><text>第二章</text></navLabel><content src="Text/ch2.xhtml"/></navPoint></navMap></ncx>"""
            )
            put(
                "OEBPS/Text/ch 1.xhtml",
                """<html><body><h2>第一章　开始</h2><p>　　他终于<span>回来</span>了。</p>
                <div><p>第二段<br/>换行</p><img src="../Images/a.jpg"/></div><p>   </p></body></html>"""
            )
            put("OEBPS/Text/ch2.xhtml", "<html><body><p>结束。</p></body></html>")
        }
        return file
    }

    @Test
    fun parsesMetadataSpineTocAndBlocks() {
        val file = sampleEpub()
        EpubBook(file).use { book ->
            assertEquals("测试书", book.title)
            assertEquals("作者", book.author)
            assertEquals(2, book.chapters.size)
            assertEquals("OEBPS/Text/ch 1.xhtml", book.chapters[0].path)
            assertEquals("第一章", book.chapters[0].title)
            assertEquals(listOf(0, 1, 0), book.toc.map { it.depth })
            val blocks = book.blocks(0)
            assertEquals(
                listOf(
                    EpubBlock.Text("第一章\u3000开始", heading = true),
                    EpubBlock.Text("他终于回来了。", heading = false),
                    EpubBlock.Text("第二段\n换行", heading = false),
                    EpubBlock.Image("OEBPS/Images/a.jpg")
                ),
                blocks
            )
        }
        file.delete()
    }

    @Test
    fun resolvesRelativePaths() {
        assertEquals("OEBPS/Images/a b.jpg", EpubBook.resolve("OEBPS/Text", "../Images/a%20b.jpg"))
        assertEquals("x.html", EpubBook.resolve("", "./x.html"))
    }

    /**
     * Real-book timing check, run only on a developer machine:
     * HANREADER_TEST_EPUB=/path/book.epub gradle :app:testDebugUnitTest
     * Prints counts and timings only, never the book's text.
     */
    @Test
    fun realBookTimings() {
        val path = System.getenv("HANREADER_TEST_EPUB")
        assumeTrue(path != null && File(path).exists())
        val seg = ZhSegmenterTest.segmenter
        val t0 = System.nanoTime()
        EpubBook(File(path!!)).use { book ->
            val openMs = (System.nanoTime() - t0) / 1_000_000
            var blocks = 0; var chars = 0L; var tokens = 0L; var worstMs = 0L; var worstChars = 0
            var unknown = 0L
            val t1 = System.nanoTime()
            for (c in book.chapters) {
                val s = System.nanoTime()
                val list = book.blocks(c.index)
                var cc = 0
                for (b in list) if (b is EpubBlock.Text) {
                    val t = seg.segment(b.text)
                    tokens += t.size; cc += b.text.length
                    unknown += t.count { it.kind == TokenKind.HAN && it.entry?.hasDefinition != true }
                }
                val ms = (System.nanoTime() - s) / 1_000_000
                if (ms > worstMs) { worstMs = ms; worstChars = cc }
                blocks += list.size; chars += cc
            }
            val allMs = (System.nanoTime() - t1) / 1_000_000
            println("EPUB open: ${openMs} ms, chapters=${book.chapters.size}, toc=${book.toc.size}")
            println("All chapters parsed+segmented: ${allMs} ms, blocks=$blocks, chars=$chars, tokens=$tokens")
            println("Slowest chapter: ${worstMs} ms for $worstChars chars (JVM, desktop CPU)")
            println("Han tokens without an English definition: $unknown")
            val hsk = File("src/main/assets/zh/hsk.tsv").bufferedReader().useLines { com.hanreader.app.reader.HskLevels.parse(it) }
            val engine = com.hanreader.app.reader.ZhEngine(ZhSegmenterTest.dict, 0, hsk)
            val ta = System.nanoTime()
            val analysis = com.hanreader.app.reader.BookAnalyzer.analyze(1, book, seg, ZhSegmenterTest.dict)
            println("Book analysis: ${(System.nanoTime() - ta) / 1_000_000} ms, distinct words=${analysis.words.size}, han tokens=${analysis.tokens}")
            val stats = com.hanreader.app.reader.VocabularyStats.book(analysis.words, analysis.tokens, emptySet(), emptySet(), engine)
            println("HSK 3.0 coverage: " + stats.levels.joinToString { "L${it.level}=${it.percent}%" })
            assertTrue(book.chapters.isNotEmpty())
        }
    }
}
