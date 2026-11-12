package com.hanreader.app.epub

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpigraphTest {
    @Test
    fun preservesVerseStanzasAttributionAndNotesFromLinkedStylesheet() {
        val file = File.createTempFile("epigraph", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun put(path: String, text: String) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
                put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""")
                put("OPS/book.opf", """<package><metadata/><manifest><item id="c" href="Text/ch.html"/></manifest><spine><itemref idref="c"/></spine></package>""")
                put("OPS/Styles/book.css", """
                    /* Publisher's quotation style, unrelated to its class name. */
                    p.quotation { margin-left: 1.42em; margin-right: 1.42em;
                        margin-top: 0; margin-bottom: 0; text-indent: 0; text-align: justify; }
                    p { text-indent: 2em; text-align: left; }
                    body { margin-left: 2em; margin-right: 2em; }
                """)
                put("OPS/Text/ch.html", """<html><head><link rel="stylesheet" href="../Styles/book.css"/></head><body>
                    <h1>Chapter</h1><div>
                    <p class="quotation">First line<sup>1</sup></p>
                    <p class="quotation">Second line</p>
                    <p class="quotation">&nbsp;</p>
                    <p class="quotation">New stanza</p>
                    <p class="quotation">&nbsp;</p>
                    <p class="quotation" style="text-align:right">Author, 2004</p>
                    </div><p>Body text</p></body></html>""")
            }
            EpubBook(file).use { book ->
                val blocks = book.blocks(0).filterIsInstance<EpubBlock.Text>()
                assertEquals(6, blocks.size)
                assertTrue(blocks[1].style.inset)
                assertEquals(false, blocks[1].style.indent)
                assertFalse(blocks[1].style.compactBefore)
                assertTrue(blocks[2].style.compactBefore)
                assertFalse(blocks[3].style.compactBefore)
                assertEquals("right", blocks[4].style.alignment)
                assertFalse(blocks[4].style.compactBefore)
                assertEquals(EpubTextStyle(), blocks[5].style)
                assertEquals(listOf(EpubNote(10, 11, "n:1")), blocks[1].notes)
            }
        } finally { file.delete() }
    }

    @Test
    fun semanticQuotesDoNotNeedPublisherClasses() {
        val doc = org.jsoup.Jsoup.parse("""<blockquote><p>Quote</p></blockquote><p>Body</p>""")
        val styles = EpubStyles(doc, emptyList())
        assertTrue(styles.textStyle(doc.select("p")[0]).inset)
        assertFalse(styles.textStyle(doc.select("p")[1]).inset)
    }
}
