package com.hanreader.app.reader

import com.hanreader.app.*
import com.hanreader.app.zh.TokenKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EntityLinksTest {
    private fun entry(name: String, id: Long = 1, aliases: List<String> = emptyList(), blocked: List<String> = emptyList()) =
        Highlight(id, 7, -1, 0, 0, 0, 2, "Заметка: $name", name, Highlights.BOOK_WIDE_TITLE,
            1, EntityLinks.encode(aliases), EntityLinks.encode(blocked))
    private fun block(text: String): ReaderBlock.Text {
        val tokens = Regex("\\p{L}+|\\s+|[^\\p{L}\\s]").findAll(text).map { m ->
            RToken(m.value, "", if (m.value.first().isLetter()) TokenKind.HAN else if (m.value.isBlank()) TokenKind.SPACE else TokenKind.PUNCT,
                m.range.first, m.range.last + 1, true)
        }.toList()
        return ReaderBlock.Text(text, false, tokens)
    }
    private fun at(rows: List<Highlight>, block: ReaderBlock.Text, text: String, last: Boolean = false): EntityLinks.Mention? {
        val start = if (last) block.text.lastIndexOf(text) else block.text.indexOf(text)
        assertTrue(start >= 0)
        return EntityLinks.at(rows, block, start, start + text.length)
    }
    @Test fun surnameInTheSameParagraphOpensTheFullNameButNotAnotherParagraph() {
        val person = entry("Osiel Cárdenas")
        val b = block("Cuando Osiel Cárdenas contrató a Guzmán, Cárdenas pidió ayuda. Cárdenas volvió.")
        val hit = at(listOf(person), b, "Cárdenas", last = true)!!
        assertEquals(person, hit.entry); assertTrue(hit.automatic)
        assertEquals(person, at(listOf(person), b, "Osiel Cárdenas")!!.entry)
        assertNull(at(listOf(person), block("Cárdenas volvió."), "Cárdenas"))
    }
    @Test fun manualVariantsWorkEverywhereAndCaseChangesButNeverInsideOtherWords() {
        val person = entry("Osiel Cárdenas", aliases = listOf("Cárdenas", "Osiel Cárdenas Guillén"))
        assertEquals(person, at(listOf(person), block("Cárdenas llegó."), "Cárdenas")!!.entry)
        assertEquals(person, at(listOf(person), block("OSIEL CÁRDENAS GUILLÉN llegó."), "OSIEL CÁRDENAS GUILLÉN")!!.entry)
        assertNull(at(listOf(person), block("Cárdenasa llegó."), "Cárdenasa"))
        assertNull(at(listOf(person), block("Cardenas llegó."), "Cardenas")) // Accents aren't guessed away.
        assertEquals(person, at(listOf(person), block("Osiel   Cárdenas llegó."), "Osiel   Cárdenas")!!.entry)
    }
    @Test fun competingOrPreviouslyUnknownFullNamesBlockAutomaticSurnameMatching() {
        val osiel = entry("Osiel Cárdenas")
        val lazaro = entry("Lázaro Cárdenas", 2)
        val b = block("Osiel Cárdenas llegó. Cárdenas habló.")
        assertNull(at(listOf(osiel, lazaro), b, "Cárdenas", last = true))
        assertNull(at(listOf(osiel, entry("Lázaro Cárdenas del Río", 3)), b, "Cárdenas", last = true))
        val mixed = block("Osiel Cárdenas llegó. Lázaro Cárdenas habló. Cárdenas salió.")
        assertNull(at(listOf(osiel), mixed, "Cárdenas", last = true))
        val particle = block("Osiel Cárdenas llegó. Francisco de Cárdenas habló. Cárdenas salió.")
        assertNull(at(listOf(osiel), particle, "Cárdenas", last = true))
        assertEquals(lazaro, at(listOf(osiel, lazaro), mixed, "Lázaro Cárdenas")!!.entry)
        assertNull(at(listOf(osiel.copy(aliasesJson = EntityLinks.encode(listOf("Cárdenas")))), mixed, "Lázaro Cárdenas"))
    }
    @Test fun explicitSeparateRecordWinsOverInferenceAndUndoRestoresOriginalNotes() {
        val osiel = entry("Osiel Cárdenas")
        val short = entry("Cárdenas", 2)
        val b = block("Osiel Cárdenas llegó. Cárdenas habló.")
        assertEquals(short, at(listOf(osiel, short), b, "Cárdenas", true)!!.entry)
        val linked = osiel.copy(aliasesJson = EntityLinks.encode(listOf("Cárdenas")))
        assertEquals(linked, EntityLinks.root(short, listOf(linked, short)))
        assertEquals(linked, at(listOf(linked, short), b, "Cárdenas", true)!!.entry)
        val detached = osiel.copy(blockedAliasesJson = EntityLinks.encode(listOf("Cárdenas")))
        assertNull(at(listOf(detached), b, "Cárdenas", true))
        assertEquals(short, at(listOf(detached, short), b, "Cárdenas", true)!!.entry)
        assertEquals("Заметка: Cárdenas", short.note)
    }
    @Test fun manualChainsResolveWithoutChangingSourceNotesAndCyclesAreRejected() {
        val a = entry("Osiel Cárdenas", aliases = listOf("Cárdenas"))
        val b = entry("Cárdenas", 2, aliases = listOf("El jefe"))
        assertEquals(a, at(listOf(a, b), block("El jefe llegó."), "El jefe")!!.entry)
        assertTrue(runCatching { EntityLinks.validate(listOf(a, b.copy(aliasesJson = EntityLinks.encode(listOf(a.text))))) }.isFailure)
        assertTrue(runCatching { EntityLinks.validate(listOf(a, entry("Otro hombre", 3, aliases = listOf("Cárdenas")))) }.isFailure)
        val otherBook = b.copy(bookId = 8)
        assertEquals(otherBook, EntityLinks.root(otherBook, listOf(a, otherBook)))
    }
    @Test fun backupKeepsWholeBookEntriesVariantsAndRejectionsWithoutDependingOnIds() {
        val a = entry("Osiel Cárdenas", aliases = listOf("Cárdenas"), blocked = listOf("Osiel"))
        val b = entry("Cárdenas", 2)
        val book = BookEntity(7, "Book", "Author", "book.epub", 1)
        val backup = Backup(1, emptyList(), emptyList(), emptyList(), library = LibraryBackup(listOf(book), emptyList(), listOf(a, b), emptyList()))
        val decoded = BackupFormat.decode(BackupFormat.encode(backup)).library!!.highlights
        assertEquals(2, decoded.size)
        assertEquals(listOf("Cárdenas"), EntityLinks.aliases(decoded.first()))
        assertEquals(listOf("Osiel"), EntityLinks.blocked(decoded.first()))
        assertEquals(a.text, EntityLinks.root(decoded.last(), decoded)!!.text)
        val old = JSONObject(BackupFormat.encode(backup)).put("version", 5)
        val rows = old.getJSONObject("library").getJSONArray("highlights")
        for (i in 0 until rows.length()) { rows.getJSONObject(i).remove("aliases"); rows.getJSONObject(i).remove("blockedAliases") }
        val legacy = BackupFormat.decode(old.toString()).library!!.highlights
        assertEquals(2, legacy.size); assertTrue(EntityLinks.aliases(legacy.first()).isEmpty())
        assertTrue(Highlights.markdown("Book", "", listOf(a, b)).contains("Варианты: Cárdenas"))
        assertTrue(runCatching { EntityLinks.parse("[42]") }.isFailure)
        assertTrue(runCatching { EntityLinks.encode(List(33) { "Name $it" }) }.isFailure)
    }
}
