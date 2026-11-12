package com.hanreader.app

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A HanReader backup file: a ZIP with backup.json (BackupFormat) and the book files under books/.
 * Older backups are the bare JSON; [read] accepts both.
 */
object BackupArchive {
    private const val JSON = "backup.json"
    private const val BOOKS = "books/"

    fun write(out: OutputStream, backup: Backup, booksDir: File) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(JSON))
            zip.write(BackupFormat.encode(backup).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            backup.library?.books?.forEach { b ->
                val f = File(booksDir, b.fileName)
                if (!f.isFile) return@forEach
                zip.putNextEntry(ZipEntry(BOOKS + b.fileName))
                f.inputStream().use { it.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads and validates a backup. Book files are unpacked into [tempDir] (emptied first); a
     * library book whose file is missing makes the whole file invalid, so nothing is half-restored.
     */
    fun read(input: InputStream, tempDir: File): Backup {
        val stream = BufferedInputStream(input)
        stream.mark(4)
        val head = ByteArray(2).also { stream.read(it) }
        stream.reset()
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            return BackupFormat.decode(stream.readBytes().toString(Charsets.UTF_8))
        }
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        var json: String? = null
        val files = HashMap<String, File>()
        ZipInputStream(stream).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.isDirectory -> Unit
                    e.name == JSON -> json = zip.readBytes().toString(Charsets.UTF_8)
                    e.name.startsWith(BOOKS) -> {
                        val name = e.name.removePrefix(BOOKS)
                        if (!BackupFormat.safeName(name)) throw BackupFormatException("Файл копии повреждён: неверное имя файла книги")
                        val f = File(tempDir, name)
                        f.outputStream().use { zip.copyTo(it, 1 shl 16) }
                        files[name] = f
                    }
                }
            }
        }
        val backup = BackupFormat.decode(json ?: throw BackupFormatException("Это не файл резервной копии ${BuildConfig.APP_NAME}."))
        backup.library?.books?.forEach { b ->
            if (b.fileName !in files) throw BackupFormatException("Файл копии повреждён: нет файла книги «${b.title}»")
        }
        return backup.copy(bookFiles = files)
    }
}
