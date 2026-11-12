package com.hanreader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.epub.EpubBook
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Must run on Android: host-JVM regex tests do not exercise Android's ICU parser. */
@RunWith(AndroidJUnit4::class)
class EpubStylesAndroidTest {
    private fun checkChapter(linked: Boolean) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("styled-epigraph", ".epub", cache)
        val css = """
            /* Exercise rule boundaries and selectors on Android's regex engine. */
            p.verse, p[data-quote] { margin-left: 1.4em; margin-right: 1.4em;
                margin-top: 0; margin-bottom: 0; text-indent: 0; text-align: left; }
        """
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun put(path: String, text: String) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
                put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""")
                put("OPS/book.opf", """<package><metadata/><manifest><item id="c" href="Text/ch.html"/></manifest><spine><itemref idref="c"/></spine></package>""")
                put("OPS/Styles/book.css", css)
                val sheet = if (linked) """<link rel="stylesheet" href="../Styles/book.css"/>""" else "<style>$css</style>"
                put("OPS/Text/ch.html", """<html><head>$sheet</head><body>
                    <h1>Chapter</h1><div><p class="verse">First line</p>
                    <p data-quote="true">Second line</p><p class="verse">&nbsp;</p>
                    <p class="verse">New stanza</p><p class="verse" style="text-align:right">Author</p></div>
                    <p>Body</p></body></html>""")
            }
            EpubBook(file).use { book ->
                val blocks = book.blocks(0).filterIsInstance<EpubBlock.Text>()
                assertEquals(6, blocks.size)
                assertTrue(blocks[1].style.inset)
                assertEquals(false, blocks[1].style.indent)
                assertTrue(blocks[2].style.compactBefore)
                assertFalse(blocks[3].style.compactBefore)
                assertEquals("right", blocks[4].style.alignment)
                assertFalse(blocks[5].style.inset)
            }
        } finally { file.delete() }
    }

    @Test fun opensChapterWithLinkedCssOnAndroid() = checkChapter(linked = true)
    @Test fun opensChapterWithInlineCssOnAndroid() = checkChapter(linked = false)
}
