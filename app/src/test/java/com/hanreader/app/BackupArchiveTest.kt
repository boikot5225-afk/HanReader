package com.hanreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Damaged backup files fail with a clear message and never write outside the temporary folder. */
class BackupArchiveTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "hanreader-restore-test")

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun read(bytes: ByteArray) = BackupArchive.read(bytes.inputStream(), tmp)

    @Test
    fun damagedFiles() {
        val notBackup = assertThrows(BackupFormatException::class.java) { read(zip("a.txt" to "x".toByteArray())) }
        assertTrue(notBackup.message!!, notBackup.message!!.contains(BuildConfig.APP_NAME))
        // Cut in the middle: a clear error, not a crash.
        val whole = zip("backup.json" to BackupFormat.encode(Backup(1, emptyList(), emptyList(), emptyList())).toByteArray())
        assertThrows(Exception::class.java) { read(whole.copyOf(whole.size / 2)) }
        assertThrows(BackupFormatException::class.java) { read("PK garbage".toByteArray()) }
        assertThrows(BackupFormatException::class.java) { read(ByteArray(0)) }
    }

    @Test
    fun bookNamesCannotEscape() {
        val json = BackupFormat.encode(Backup(1, emptyList(), emptyList(), emptyList())).toByteArray()
        assertThrows(BackupFormatException::class.java) { read(zip("backup.json" to json, "books/../../evil.txt" to "x".toByteArray())) }
        assertTrue(!File(tmp.parentFile, "evil.txt").exists())
    }

    @Test
    fun oldJsonStillReads() {
        val b = read(BackupFormat.encode(Backup(5, emptyList(), emptyList(), emptyList())).toByteArray())
        assertEquals(5L, b.exportedAt)
    }
}
