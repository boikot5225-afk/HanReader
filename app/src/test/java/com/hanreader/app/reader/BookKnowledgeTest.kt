package com.hanreader.app.reader

import com.hanreader.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BookKnowledgeTest {
    private fun root(name: String = "Osiel Cárdenas", id: Long = 1, aliases: List<String> = listOf("Cárdenas")) =
        Highlight(id, 7, -1, 0, 0, 0, 2, "Общее объяснение", name, "Во всей книге", aliasesJson = EntityLinks.encode(aliases))
    private fun quote(name: String = "Cárdenas", id: Long = 3, owner: String = "Osiel Cárdenas", context: String = "Cárdenas pidió ayuda.") =
        Highlight(id, 7, 2, 9, 11, 11 + name.length, 2, "Личная заметка", name, "Глава 3", entityName = owner, contextQuote = context)

    @Test fun rootsCollapseButOriginalNotesAndQuotesRemainAndCanBeSearched() {
        val a = root()
        val b = root("Cárdenas", 2, listOf("El jefe")).copy(note = "Прежняя заметка")
        val q = quote(owner = "El jefe")
        val entry = BookKnowledge.entries(7, listOf(a, b, q)).single()
        assertEquals(a, entry.record); assertEquals(listOf(q), entry.quotes)
        assertTrue(entry.variants.containsAll(listOf("Cárdenas", "El jefe")))
        assertEquals(listOf(b), entry.originals)
        assertEquals(1, BookKnowledge.entries(7, listOf(a, b, q), "прежняя").size)
        assertEquals(1, BookKnowledge.entries(7, listOf(a, b, q), "pidió").size)
        assertTrue(BookKnowledge.entries(7, listOf(a, b, q), "отсутствует").isEmpty())
    }

    @Test fun legacyWikipediaNotesAppearAndOnlyUniqueExactNamesAttach() {
        val a = root()
        val wiki = quote(owner = "", context = "").copy(note = "Описание\n\nКонтекст книги:\nCárdenas habló.\n\nИсточник: Википедия — Osiel\nhttps://es.wikipedia.org/w/index.php?oldid=42")
        assertEquals(a, BookKnowledge.owner(wiki, listOf(a)))
        assertEquals("Cárdenas habló.", BookKnowledge.quote(wiki))
        val unrelated = wiki.copy(id = 4, text = "Guzmán")
        val ordinary = wiki.copy(id = 5, note = "Обычная ручная заметка")
        val all = BookKnowledge.entries(7, listOf(a, wiki, unrelated, ordinary))
        assertEquals(2, all.size)
        assertEquals(listOf(wiki), all.first { it.shared }.quotes)
        assertEquals(unrelated, all.first { !it.shared }.record)
        assertNull(BookKnowledge.owner(wiki.copy(text = "Cárdenas"), listOf(root(aliases = emptyList()))))
        assertNull(BookKnowledge.owner(wiki, listOf(a, root("Lázaro Cárdenas", 8))))
    }

    @Test fun detachOrDeleteOwnerNeverLosesSavedFragmentsOrReattachesLegacyNotes() {
        val a = root(); val q = quote()
        assertEquals(q, BookKnowledge.entries(7, listOf(q)).single().record)
        val detached = q.copy(entityName = "", note = "Источник: Википедия — Osiel")
        assertNull(BookKnowledge.owner(detached, listOf(a)))
        assertEquals(2, BookKnowledge.entries(7, listOf(a, detached)).size)
        assertTrue(BookKnowledge.entries(8, listOf(a, q)).isEmpty())
        assertNull(BookKnowledge.owner(q.copy(bookId = 8), listOf(a)))
    }

    @Test fun backupRoundTripKeepsReferencesAcrossRenumberingAndOldBackupsDefaultSafely() {
        val rows = listOf(root(), quote())
        val backup = Backup(1, emptyList(), emptyList(), emptyList(), library = LibraryBackup(
            listOf(BookEntity(id = 7, title = "Книга", author = "Автор", fileName = "book.epub", chapterCount = 3)), emptyList(), rows, emptyList()))
        val encoded = BackupFormat.encode(backup)
        val decoded = BackupFormat.decode(encoded).library!!.highlights
        assertEquals("Osiel Cárdenas", decoded.last().entityName)
        assertEquals(rows.last().contextQuote, decoded.last().contextQuote)
        val restored = decoded.mapIndexed { i, h -> h.copy(id = 100L + i) }
        assertEquals(1, BookKnowledge.entries(7, restored).single().quotes.size)
        val old = JSONObject(encoded).put("version", 6)
        val h = old.getJSONObject("library").getJSONArray("highlights")
        for (i in 0 until h.length()) { h.getJSONObject(i).remove("entityName"); h.getJSONObject(i).remove("contextQuote") }
        assertTrue(BackupFormat.decode(old.toString()).library!!.highlights.all { it.entityName.isEmpty() && it.contextQuote.isEmpty() })
        h.getJSONObject(1).put("entityName", 10)
        assertThrows(BackupFormatException::class.java) { BackupFormat.decode(old.toString()) }
    }

    @Test fun exportIncludesSourcesAliasesOriginalNotesAndChapterExcerpts() {
        val a = root().copy(note = "Определение\nИсточник: Википедия — Osiel\nhttps://es.wikipedia.org/w/index.php?oldid=42\nCC BY-SA 4.0")
        val q = quote()
        val text = BookKnowledge.markdown("Книга", "Автор", BookKnowledge.entries(7, listOf(a, q)))
        listOf("Книга", "Автор", "Osiel Cárdenas", "Cárdenas", "oldid=42", "CC BY-SA 4.0", "Глава 3", "> Cárdenas pidió ayuda.", "Личная заметка").forEach { assertTrue(it, text.contains(it)) }
        BookKnowledge.validate(listOf(a, q))
        assertThrows(IllegalArgumentException::class.java) { BookKnowledge.validate(listOf(a.copy(contextQuote = "bad"))) }
    }
}
