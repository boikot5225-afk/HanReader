package com.hanreader.app.reader

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.Backup
import com.hanreader.app.BookWord
import com.hanreader.app.KnownWord
import com.hanreader.app.LexiconDatabase
import com.hanreader.app.LexiconViewModel
import com.hanreader.app.ReaderSave
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The backup file carries the whole library: books, positions, bookmarks, highlights, marks, sources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullBackupTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < end) { "Timed out" }
            Thread.sleep(20)
        }
    }

    private fun sampleBook(): File {
        val file = File(app.cacheDir, "sample.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""")
            put("content.opf", """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>雨夜</dc:title></metadata><manifest>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""")
            put("a.xhtml", "<html><body><h1>第一章</h1><p>那天晚上一直在下雨。他站在门口，等一个不会来的人。</p><p>门开了，老板看了他一眼。</p></body></html>")
        }
        return file
    }

    /** The database outlives a test in this JVM: leave it empty for the next test class. */
    @org.junit.After
    fun cleanUp() = runBlocking {
        val db = LexiconDatabase.get(app)
        db.knownWordDao().removeAll()
        db.bookDao().deleteAll()
        db.cardDao().replaceAll(emptyList(), emptyList())
        db.statsDao().clearDays()
    }

    @Test
    fun libraryRoundTrip() {
        val reader = ReaderViewModel(app)
        var imported: Result<Long>? = null
        reader.importBook(Uri.fromFile(sampleBook())) { imported = it }
        waitFor { imported != null }
        val bookId = imported!!.getOrThrow()
        reader.openBook(bookId)
        waitFor { reader.open.value?.chapter != null }
        val chapter = reader.open.value!!.chapter!!
        val db = LexiconDatabase.get(app)
        reader.savePosition(0, 2, 5)
        reader.addBookmark(chapter, 1)
        reader.addHighlight(chapter, 1, 0, 6, 2)
        reader.setKnown("下雨", false)
        var saved: Result<Boolean>? = null
        reader.saveWord(ReaderSave("老板", "lǎobǎn", "хозяин", "门开了，老板看了他一眼。", bookId, "雨夜", 0, "第一章"), { saved = it })
        waitFor { saved != null && runBlocking { db.highlightDao().all().size == 1 && db.bookmarkDao().all().size == 1 } }
        runBlocking { db.bookDao().insertWords(listOf(BookWord(bookId, "老板", 1, 0, ignored = true))) }
        waitFor { runBlocking { db.bookDao().get(bookId)!!.charOffset == 5 } }
        val highlight = runBlocking { db.highlightDao().all().single() }
        runBlocking {
            db.statsDao().addReading(20261105, 600_000, 2_000)
            db.statsDao().putProgress(com.hanreader.app.BookProgress(bookId, 20261105, 61))
        }

        // Export.
        val lexicon = LexiconViewModel(app)
        val out = File(app.cacheDir, "copy.zip")
        var exported: Result<Int>? = null
        lexicon.exportBackup(Uri.fromFile(out)) { exported = it }
        waitFor { exported != null }
        assertEquals(runBlocking { db.cardDao().allCards().size }, exported!!.getOrThrow())
        ZipFile(out).use { z -> assertTrue(z.entries().toList().map { it.name }.containsAll(listOf("backup.json", "books/" + runBlocking { db.bookDao().get(bookId)!!.fileName }))) }

        // Lose everything: the book (with its bookmarks and highlights), the marks.
        val fileName = runBlocking { db.bookDao().get(bookId)!!.fileName }
        runBlocking { db.bookDao().delete(bookId); db.knownWordDao().removeAll(); db.statsDao().clearDays() }
        File(app.filesDir, "books/$fileName").delete()

        // Restore.
        var read: Result<Backup>? = null
        lexicon.readBackup(Uri.fromFile(out)) { read = it }
        waitFor { read != null }
        val backup = read!!.getOrThrow()
        assertEquals(1, backup.library!!.books.size)
        var restored: Result<Unit>? = null
        lexicon.restoreBackup(backup) { restored = it }
        waitFor { restored != null }
        restored!!.getOrThrow()

        val book = runBlocking { db.bookDao().get(bookId) }!!
        assertEquals(listOf(0, 2, 5), listOf(book.chapterIndex, book.blockIndex, book.charOffset))
        assertTrue(File(app.filesDir, "books/$fileName").isFile)
        assertEquals(1, runBlocking { db.bookmarkDao().observe(bookId).first() }.size)
        val h = runBlocking { db.highlightDao().observe(bookId).first() }.single()
        assertEquals(listOf(highlight.startChar, highlight.endChar, highlight.color, highlight.text), listOf(h.startChar, h.endChar, h.color, h.text))
        assertEquals(listOf("老板"), runBlocking { db.bookDao().ignoredWords(bookId) })
        assertEquals(KnownWord("下雨", ReaderViewModel.UNKNOWN).source, runBlocking { db.knownWordDao().all() }.single().source)
        val source = runBlocking { db.cardDao().allSources() }.single { it.sentence.contains("老板") }
        assertEquals("门开了，老板看了他一眼。", source.sentence)
        assertEquals(2_000, runBlocking { db.statsDao().allDays() }.single { it.day == 20261105 }.readChars)
        assertEquals(61, runBlocking { db.statsDao().allProgress() }.single { it.bookId == bookId }.percent)
        // The restored book opens.
        val again = ReaderViewModel(app)
        again.openBook(bookId)
        waitFor { again.open.value?.chapter != null || again.open.value?.error != null }
        assertEquals(null, again.open.value!!.error)
    }
}
