package com.hanreader.app.epub

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

class EpubSpacingTest {
    private fun book(html: String, css: String = ""): List<EpubBlock.Text> {
        val file = File.createTempFile("spacing", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { z ->
                fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
                put("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>")
                put("book.opf", "<package><metadata/><manifest><item id=\"a\" href=\"a.html\"/></manifest><spine><itemref idref=\"a\"/></spine></package>")
                put("a.html", "<html><head><style>$css</style></head><body>$html</body></html>")
            }
            return EpubBook(file).use { it.blocks(0).filterIsInstance<EpubBlock.Text>() }
        } finally { file.delete() }
    }

    @Test fun trailingDoubleBreakPreservesSceneGapWithoutChangingTextOrNoteOffsets() {
        val b = book("<p>First<sup>1</sup><br/><br/></p><p>Second<br/></p><p>Third</p>")
        assertEquals(listOf("First1", "Second", "Third"), b.map { it.text })
        assertEquals(listOf(EpubNote(5, 6, "n:1")), b[0].notes)
        assertEquals(1.2f, b[0].style.spaceAfterEm, 0.001f)
        assertEquals(0f, b[1].style.spaceAfterEm, 0f)
        assertEquals(0f, b[2].style.spaceBeforeEm, 0f)
    }

    @Test fun blankFiveBreakParagraphIsFoldedIntoTheHeadingWithoutAddingABlock() {
        val b = book("<div><p>&nbsp;<br/><br/><br/><br/><br/></p><h1>Chapter</h1><p>Body</p><p>   </p><p>Next</p></div>")
        assertEquals(listOf("Chapter", "Body", "Next"), b.map { it.text })
        assertEquals(6f, b[0].style.spaceBeforeEm, 0.001f)
        assertEquals(0f, b[1].style.spaceBeforeEm, 0f)
        assertEquals(0f, b[2].style.spaceBeforeEm, 0f)
    }

    @Test fun cssShorthandAndContainerMarginsApplyOnlyAtTheOuterBoundaries() {
        val b = book("<p class=\"scene\">A</p><div class=\"group\"><p>B</p><p>C</p></div><p>D</p>",
            "p.scene { margin:3em 0 2em; margin-top:4em } .group {margin:2em 1em}")
        assertEquals(4f, b[0].style.spaceBeforeEm, 0f)
        assertEquals(2f, b[0].style.spaceAfterEm, 0f)
        assertEquals(2f, b[1].style.spaceBeforeEm, 0f)
        assertEquals(0f, b[1].style.spaceAfterEm, 0f)
        assertEquals(0f, b[2].style.spaceBeforeEm, 0f)
        assertEquals(2f, b[2].style.spaceAfterEm, 0f)
        assertEquals(0f, b[3].style.spaceBeforeEm, 0f)
    }

    @Test fun emptyHeightAndNonbreakingSpacePreserveTheirGapButNotFormattingIndentation() {
        val b = book("<p>A</p><div style=\"height:3em\"></div><p>B</p><p>&nbsp;</p><p>C</p><div>  </div><p>D</p>")
        assertEquals(listOf("A", "B", "C", "D"), b.map { it.text })
        assertEquals(3f, b[1].style.spaceBeforeEm, 0f)
        assertEquals(1.2f, b[2].style.spaceBeforeEm, 0.001f)
        assertEquals(0f, b[3].style.spaceBeforeEm, 0f)
    }

    @Test fun sceneOpeningAndCenteredAttributionKeepTheirExplicitFormatting() {
        val b = book("<p class=\"noindent\">Opening</p><p class=\"right\">Author</p><p>Ordinary</p>",
            ".noindent {text-indent:0} .right {text-align:right} p {text-indent:2em}")
        assertEquals(false, b[0].style.indent)
        assertEquals("right", b[1].style.alignment)
        assertEquals(EpubTextStyle(), b[2].style)
    }

    @Test fun oversizedGapsAreBoundedAndNumericUnitsAreConvertedToReaderEm() {
        val b = book("<p style=\"margin-top:100000em\">A</p><p style=\"margin:32px 0 24pt\">B</p><p>" + "<br/>".repeat(1000) + "</p><p>C</p>")
        assertEquals(8f, b[0].style.spaceBeforeEm, 0f)
        assertEquals(2f, b[1].style.spaceBeforeEm, 0f)
        assertEquals(2f, b[1].style.spaceAfterEm, 0f)
        assertEquals(8f, b[2].style.spaceBeforeEm, 0f)
    }

    /** Optional, uploaded book stays outside the repository; report contains counts only. */
    @Test fun uploadedBookRetainsItsSceneBreaks() {
        val path = System.getenv("HANREADER_TEST_EPUB")
        assumeTrue(path != null && File(path).isFile)
        var blocks = 0; var scenes = 0; var largeBefore = 0; var chars = 0L
        val started = System.nanoTime()
        EpubBook(File(path!!)).use { b ->
            for (chapter in b.chapters) for (block in b.blocks(chapter.index)) {
                blocks++
                if (block is EpubBlock.Text) {
                    chars += block.text.length
                    if (block.style.spaceAfterEm >= 1.2f) scenes++
                    if (block.style.spaceBeforeEm >= 6f) largeBefore++
                    assertTrue(block.style.spaceBeforeEm.isFinite() && block.style.spaceBeforeEm in 0f..8f)
                    assertTrue(block.style.spaceAfterEm.isFinite() && block.style.spaceAfterEm in 0f..8f)
                }
            }
            assertEquals(161, b.chapters.size)
            assertTrue("all 308 double-break scene endings survived: $scenes", scenes >= 308)
            assertTrue("all 135 five-break chapter openings survived: $largeBefore", largeBefore >= 135)
            val report = JSONObject().put("chapters", b.chapters.size).put("blocks", blocks).put("chars", chars)
                .put("scene_endings", scenes).put("large_openings", largeBefore)
                .put("parse_ms", (System.nanoTime() - started) / 1_000_000)
            File("build/reports/epub-spacing-audit.json").apply { parentFile?.mkdirs(); writeText(report.toString(2)) }
        }
    }
}
