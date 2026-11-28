package com.hanreader.app

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A HanReader backup file: a ZIP with backup.json (BackupFormat), the book files under books/ and
 * card pictures under card-images/<card id>.jpg.
 * Older backups are the bare JSON; [read] accepts both.
 */
object BackupArchive {
    private const val JSON = "backup.json"
    private const val BOOKS = "books/"
    private const val IMAGES = "card-images/"

    fun write(out: OutputStream, backup: Backup, booksDir: File, imagesDir: File? = null) {
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
            if (imagesDir != null) backup.cards.forEach { c ->
                val f = File(imagesDir, "${c.id}.jpg")
                if (!f.isFile) return@forEach
                zip.putNextEntry(ZipEntry(IMAGES + f.name))
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
        val images = HashMap<Long, File>()
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
                    e.name.startsWith(IMAGES) -> {
                        // A picture whose name is not a card id is skipped, never written outside tempDir.
                        val id = e.name.removePrefix(IMAGES).removeSuffix(".jpg").takeIf { e.name.endsWith(".jpg") }?.toLongOrNull()
                        if (id != null && id > 0) {
                            val dir = File(tempDir, IMAGES).apply { mkdirs() }
                            val f = File(dir, "$id.jpg")
                            f.outputStream().use { zip.copyTo(it, 1 shl 16) }
                            images[id] = f
                        }
                    }
                }
            }
        }
        val backup = BackupFormat.decode(json ?: throw BackupFormatException("Это не файл резервной копии ${BuildConfig.APP_NAME}."))
        backup.library?.books?.forEach { b ->
            if (b.fileName !in files) throw BackupFormatException("Файл копии повреждён: нет файла книги «${b.title}»")
        }
        val ids = backup.cards.mapTo(HashSet()) { it.id }
        return backup.copy(bookFiles = files, cardImages = images.filterKeys { it in ids })
    }
}
