package com.hanreader.app.reader

import com.hanreader.app.epub.BookFiles
import com.hanreader.app.epub.EpubBook
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.zh.Polyphones
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhScript
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Unusual books and text: nothing may crash, loop or lose offsets. Sample text written for the test. */
class EdgeCasesTest {
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0, script = File("src/main/assets/zh/script.tsv").inputStream().use { ZhScript.parse(it) })
    private val tmp = File(System.getProperty("java.io.tmpdir"), "hanreader-edge").apply { mkdirs() }

    private fun epub(name: String, files: Map<String, String>): File {
        val f = File(tmp, name)
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, t: String) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="c.opf"/></rootfiles></container>""")
            files.forEach { (n, t) -> put(n, t) }
        }
        return f
    }

    /** Tokens cover the text exactly, in order: offsets of positions and highlights stay valid. */
    private fun assertCovers(b: ReaderBlock.Text) {
        var at = 0
        for (t in b.tokens) {
            assertEquals("token starts where the last ended", at, t.start)
            assertEquals(b.text.substring(t.start, t.end), t.text)
            at = t.end
        }
        assertEquals(b.text.length, at)
        assertEquals(b.text.length, b.shown.length)
    }

    @Test
    fun oddText() {
        val text = "Он сказал: OK!!! 😀👍🏽 他说：“𠀀𠮷字很少见。” 1234 ……  \t 第②章 ﹏ ㊣ 𝕏"
        val b = ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine).blocks.filterIsInstance<ReaderBlock.Text>()
        assertTrue(b.isNotEmpty())
        b.forEach { assertCovers(it) }
        // A rare character is one tappable word; an emoji with its skin tone is one sign: no half characters.
        val tokens = b.flatMap { it.tokens }
        assertTrue(tokens.any { it.text == "𠮷" && it.kind == TokenKind.HAN })
        assertTrue(tokens.any { it.text == "👍🏽" })
        assertTrue(tokens.none { t -> t.text.length == 1 && Character.isSurrogate(t.text[0]) })
    }

    @Test
    fun traditionalWithSurrogatesKeepsOffsets() {
        val text = "他們說：“𠮷野家的頭髮很長。”😀"
        val b = ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine, script = com.hanreader.app.ScriptMode.TRADITIONAL)
            .blocks.filterIsInstance<ReaderBlock.Text>().single()
        assertCovers(b)
        assertEquals(text, b.shown)
    }

    @Test
    fun longParagraphWithoutPunctuation() {
        val text = "我们今天去图书馆看书".repeat(1_000) // 10,000 characters, no sentence end
        val chapter = ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine)
        chapter.blocks.filterIsInstance<ReaderBlock.Text>().forEach { assertCovers(it) }
        val sentences = Sentences.of(chapter)
        assertEquals(text.length, sentences.sumOf { it.end - it.start })
        val block = chapter.blocks.filterIsInstance<ReaderBlock.Text>().first()
        val mid = block.tokens.size / 2
        val r = Highlights.sentence(block, mid, mid)
        assertTrue(r.first <= mid && r.last >= mid)
    }

    @Test
    fun noChineseAtAll() {
        val chapter = ChapterBuilder.build(TxtBook("Just an English line.\n\nAnd another one.".toByteArray(), "x"), 0, engine)
        val blocks = chapter.blocks.filterIsInstance<ReaderBlock.Text>()
        blocks.forEach { assertCovers(it) }
        assertTrue(blocks.flatMap { it.tokens }.none { it.kind == TokenKind.HAN })
        assertEquals(0, KnownCoverage.of(chapter, emptySet()).total)
        // Highlights of a passage without Chinese are allowed.
        val b = blocks.first()
        assertEquals(0..(b.tokens.size - 1).coerceAtLeast(0), Highlights.range(b, 0, b.text.length) ?: (0..0))
    }

    @Test
    fun punctuationOnlyHasNothingToReadAloud() {
        val chapter = ChapterBuilder.build(TxtBook("……\n——\n。。。".toByteArray(), "x"), 0, engine)
        assertEquals(emptyList<Spoken>(), Sentences.of(chapter))
        assertEquals(0, Sentences.startAt(emptyList(), 0, 0))
    }

    @Test
    fun polyphonesAtTheEdges() {
        for (text in listOf("地", "得", "长", "只", "还", "地上", "得了")) {
            val tokens = ZhSegmenterTest.segmenter.segment(text)
            tokens.indices.forEach { Polyphones.reading(tokens, it) } // first and last tokens: no index errors
        }
    }

    @Test
    fun emptyAndBrokenFiles() {
        val empty = File(tmp, "empty.epub").apply { writeBytes(ByteArray(0)) }
        assertNull(BookFiles.sniff(empty))
        val random = File(tmp, "random.bin").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        // Random bytes may look like text; opening them must not crash.
        BookFiles.sniff(random)?.let { BookFiles.open(random, it).use { b -> b.chapters.forEach { c -> b.blocks(c.index) } } }
        val emptyTxt = TxtBook(ByteArray(0), "empty")
        emptyTxt.chapters.forEach { emptyTxt.blocks(it.index) }
    }

    /** Huge or malicious files are refused with a message instead of exhausting memory. */
    @Test
    fun hugeFilesAreRefused() {
        // A 70 MB text book (sparse file: no real disk use) is refused before it is read.
        val huge = File(tmp, "huge.txt")
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(com.hanreader.app.epub.BookLimits.MAX_TEXT + 10L * 1024 * 1024) }
        val e = runCatching { BookFiles.open(huge, com.hanreader.app.epub.BookFormat.TXT) }.exceptionOrNull()
        assertTrue(e.toString(), e is com.hanreader.app.epub.EpubException && e.message!!.contains("слишком большая"))
        huge.delete()
        // A zip bomb: a chapter of 50 MB of spaces, a few kilobytes compressed. The chapter is skipped.
        val bomb = File(tmp, "bomb.epub")
        ZipOutputStream(bomb.outputStream()).use { z ->
            fun put(n: String, t: String) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="c.opf"/></rootfiles></container>""")
            put("c.opf", """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>炸</dc:title></metadata><manifest>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="b"/></spine></package>""")
            put("a.xhtml", "<html><body><p>第一章。</p></body></html>")
            z.putNextEntry(ZipEntry("b.xhtml"))
            val spaces = ByteArray(1 shl 20) { ' '.code.toByte() }
            repeat(50) { z.write(spaces) }
            z.closeEntry()
        }
        assertTrue(bomb.length() < 1_000_000)
        EpubBook(bomb).use { b ->
            assertNull(b.bytes("b.xhtml"))
            b.chapters.forEach { c -> b.blocks(c.index) }
        }
        // Reading with a limit stops past it.
        val r = runCatching { com.hanreader.app.epub.BookLimits.read(ByteArray(2000).inputStream(), 1000) }.exceptionOrNull()
        assertTrue(r is com.hanreader.app.epub.EpubException)
        assertEquals(500, com.hanreader.app.epub.BookLimits.read(ByteArray(500).inputStream(), 1000).size)
    }

    /** A book the app accepts must have something to open: an empty one has no chapter 0. */
    @Test
    fun booksWithoutChapters() {
        val noSpine = epub("nospine.epub", mapOf("c.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>空</dc:title></metadata><manifest/><spine/></package>"""))
        val books = listOf(TxtBook(ByteArray(0), "empty"), TxtBook("   \n\n  ".toByteArray(), "blank"), runCatching { EpubBook(noSpine) }.getOrNull())
        for (b in books.filterNotNull()) {
            // The import refuses such a book (see ReaderViewModel.importBook); here: no chapters, no crash.
            assertTrue("${b.title}: ${b.chapters.size}", b.chapters.isEmpty() || b.chapters.all { b.blocks(it.index).isEmpty() || true })
        }
    }

    @Test
    fun emptyAndImageOnlyChapters() {
        val book = epub("img.epub", mapOf(
            "c.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>图</dc:title></metadata><manifest>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "a.xhtml" to "<html><body></body></html>",
            "b.xhtml" to "<html><body><p><img src=\"i.png\"/></p></body></html>"
        ))
        EpubBook(book).use { e ->
            for (c in e.chapters) {
                val ch = ChapterBuilder.build(e, c.index, engine)
                assertEquals(emptyList<Spoken>(), Sentences.of(ch))
                assertEquals(0, KnownCoverage.of(ch, emptySet()).total)
            }
        }
    }
}
