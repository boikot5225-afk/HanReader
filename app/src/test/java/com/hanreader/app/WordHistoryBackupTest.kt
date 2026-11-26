package com.hanreader.app

import com.hanreader.app.reader.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class WordHistoryBackupTest {
    private val fragment = ReadingFragment("a".repeat(64), "book:1", 1, "Книга", "Глава", 0, 0, 0, "Hablo y hablamos.")
    private fun word(f: ReadingFragment, start: Int, end: Int) = WordEncounter(
        WordHistory.wordId(f.id, start, end, "hablar"), f.id, "es-ES", "hablar",
        f.text.substring(start, end), start, end, 123, 4)
    private val wiki = fragment.copy(id = "b".repeat(64), sourceKey = "wiki:es:42", bookId = null, title = "Статья")
    private val history = WordHistoryBackup(listOf(fragment, wiki),
        listOf(word(fragment, 0, 5).copy(firstWithoutHint = 456L), word(wiki, 8, 16)), false)
    private val library = LibraryBackup(listOf(BookEntity(1, "Книга", "", "book.txt", 1)), emptyList(), emptyList(), emptyList())
    private fun backup() = Backup(100, emptyList(), emptyList(), emptyList(), library = library, wordHistory = history)

    @Test fun roundTripKeepsEverySourceIdCounterDateAndCollectionPreference() {
        val encoded = BackupFormat.encode(backup())
        assertEquals(8, JSONObject(encoded).getInt("version"))
        assertEquals(history, BackupFormat.decode(encoded).wordHistory)
        assertFalse(encoded.contains("apiKey"))
    }
    @Test fun zipCarriesBookAndArticleHistory() {
        val dir = Files.createTempDirectory("history-backup").toFile()
        try {
            java.io.File(dir, "book.txt").writeText(fragment.text)
            val bytes = ByteArrayOutputStream().also { BackupArchive.write(it, backup(), dir) }.toByteArray()
            val restored = BackupArchive.read(bytes.inputStream(), java.io.File(dir, "unpacked"))
            assertEquals(history, restored.wordHistory)
            assertEquals(fragment.text, restored.bookFiles.getValue("book.txt").readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun everyPreviousBackupVersionStillLoadsWithoutHistory() {
        for (version in 1..7) {
            val root = JSONObject(BackupFormat.encode(backup()))
            root.remove("wordHistory"); root.put("version", version)
            assertNull(BackupFormat.decode(root.toString()).wordHistory)
        }
    }
    @Test fun corruptRowsAndCoercedTypesAreRejected() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("fragmentId", "c".repeat(64)) }, { it.put("lookups", -1) },
            { it.put("lookups", "4") }, { it.put("start", 0.5) }, { it.put("end", 999999) },
            { it.put("firstSeen", -1) }, { it.put("firstWithoutHint", -1) },
            { it.put("language", "bogus") }, { it.put("original", "wrong") },
            { it.put("lemma", "changed") }, { it.put("id", "c".repeat(64)) }
        )
        for (mutate in mutations) {
            val root = JSONObject(BackupFormat.encode(backup()))
            mutate(root.getJSONObject("wordHistory").getJSONArray("words").getJSONObject(0))
            assertThrows(BackupFormatException::class.java) { BackupFormat.decode(root.toString()) }
        }
    }
    @Test fun invalidGraphSourcesAndMissingSectionsAreRejectedBeforeRestore() {
        for (mode in 0..7) {
            val root = JSONObject(BackupFormat.encode(backup()))
            val section = root.getJSONObject("wordHistory")
            when (mode) {
                0 -> section.getJSONArray("words").put(section.getJSONArray("words").getJSONObject(0))
                1 -> section.getJSONArray("fragments").put(section.getJSONArray("fragments").getJSONObject(0))
                2 -> section.getJSONArray("fragments").getJSONObject(0).put("bookId", 999)
                3 -> section.getJSONArray("fragments").getJSONObject(1).put("sourceKey", "https://evil.example/")
                4 -> root.remove("library")
                5 -> section.remove("words")
                6 -> section.put("enabled", "false")
                7 -> section.getJSONArray("words").getJSONObject(1).put("language", "en-US")
            }
            assertThrows(BackupFormatException::class.java) { BackupFormat.decode(root.toString()) }
        }
        assertThrows(BackupFormatException::class.java) {
            BackupFormat.encode(backup().copy(wordHistory = history.copy(words = history.words.map { it.copy(lookups = -1) })))
        }
    }
}
