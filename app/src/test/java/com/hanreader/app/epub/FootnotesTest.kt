package com.hanreader.app.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Footnotes of EPUB books: linked ones and bare superscript numbers with a notes section. Own sample text. */
class FootnotesTest {
    private fun book(): File {
        val file = File.createTempFile("notes", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<package><metadata><title>Notas</title></metadata>
                <manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="Text/1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/2.xhtml" media-type="application/xhtml+xml"/>
                <item id="n" href="Text/Notas.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/><itemref idref="n"/></spine></package>""")
            put("OEBPS/toc.ncx", """<ncx><navMap>
                <navPoint><navLabel><text>1. Puerto</text></navLabel><content src="Text/1.xhtml"/></navPoint>
                <navPoint><navLabel><text>2. Aduana</text></navLabel><content src="Text/2.xhtml"/></navPoint>
                <navPoint><navLabel><text>Notas</text></navLabel><content src="Text/Notas.xhtml"/></navPoint></navMap></ncx>""")
            put("OEBPS/Text/1.xhtml", """<html><body><p>El barco llegó tarde<sup><span>1</span></sup> y nadie lo esperaba.</p>
                <p>Marta firmó<a href="#fn" epub:type="noteref">*</a> los papeles.</p>
                <aside id="fn" epub:type="footnote"><p>Una firma provisional.</p></aside></body></html>""")
            put("OEBPS/Text/2.xhtml", """<html><body><p>El señor Ortega<sup>1</sup> la esperaba.</p></body></html>""")
            put("OEBPS/Text/Notas.xhtml", """<html><body><p>Notas</p>
                <p>Capítulo 1: Puerto</p><p>1. El barco venía de Génova.</p>
                <p>Capítulo 2: Aduana</p><p>1. Funcionario de la aduana desde 1990.</p></body></html>""")
        }
        return file
    }

    @Test
    fun marksAndTheirTexts() {
        EpubBook(book()).use { b ->
            val p = b.blocks(0).filterIsInstance<EpubBlock.Text>()
            assertEquals("El barco llegó tarde1 y nadie lo esperaba.", p[0].text)
            val n = p[0].notes.single()
            assertEquals("1", p[0].text.substring(n.start, n.end))
            assertEquals("El barco venía de Génova.", b.note(0, n.ref))
            val linked = p[1].notes.single()
            assertEquals("Una firma provisional.", b.note(0, linked.ref))
            // The same number in another chapter is that chapter's note.
            val other = b.blocks(1).filterIsInstance<EpubBlock.Text>()[0].notes.single()
            assertEquals("Funcionario de la aduana desde 1990.", b.note(1, other.ref))
            assertNull(b.note(0, "n:9"))
        }
    }
}
