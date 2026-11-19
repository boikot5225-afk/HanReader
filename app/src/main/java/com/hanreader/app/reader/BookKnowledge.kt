package com.hanreader.app.reader

import com.hanreader.app.Highlight

/** A view of existing notes and their saved passages, never a second dictionary. */
object BookKnowledge {
    data class Entry(val record: Highlight, val variants: List<String>, val originals: List<Highlight>, val quotes: List<Highlight>) {
        val shared get() = record.chapterIndex == Highlights.BOOK_WIDE
    }

    fun validate(rows: List<Highlight>) {
        rows.forEach {
            require(it.entityName.length <= 200 && !it.entityName.any(Char::isISOControl) && it.contextQuote.length <= 5_000)
            if (it.entityName.isNotEmpty()) require(it.entityName.isNotBlank() && it.entityName.any(Char::isLetter))
            if (it.chapterIndex == Highlights.BOOK_WIDE) require(it.entityName.isEmpty() && it.contextQuote.isEmpty())
            else if (it.entityName.isNotEmpty() || it.contextQuote.isNotEmpty()) require(it.chapterIndex >= 0 && it.blockIndex >= 0 && it.startChar >= 0 && it.endChar > it.startChar)
        }
    }

    fun isWikipedia(h: Highlight) = h.note.contains("Источник: Википедия — ")
    fun quote(h: Highlight): String = h.contextQuote.ifBlank {
        h.note.substringAfter("Контекст книги:\n", "").substringBefore("\n\nИсточник:").trim().ifBlank { h.text }
    }

    /** Exact canonical names and explicit aliases only. No surname inference across a book. */
    fun owner(h: Highlight, wide: List<Highlight>): Highlight? {
        val name = h.entityName.ifBlank {
            if (h.contextQuote.isEmpty() && isWikipedia(h)) h.text else return null
        }
        val matching = wide.filter { it.bookId == h.bookId &&
            (EntityLinks.key(it.text) == EntityLinks.key(name) || EntityLinks.aliases(it).any { alias -> EntityLinks.key(alias) == EntityLinks.key(name) }) }
            .mapNotNull { EntityLinks.root(it, wide) }.distinct()
        return matching.singleOrNull()
    }

    fun entries(book: Long, highlights: List<Highlight>, query: String = ""): List<Entry> {
        val rows = highlights.filter { it.bookId == book }
        val wide = rows.filter { it.chapterIndex == Highlights.BOOK_WIDE }
        val quotes = rows.filter { it.chapterIndex >= 0 && (it.entityName.isNotEmpty() || it.contextQuote.isNotEmpty() || isWikipedia(it)) }
        val grouped = quotes.groupBy { owner(it, wide) }
        val all = EntityLinks.roots(wide).map { root ->
            val members = wide.filter { it != root && EntityLinks.root(it, wide) == root }
            Entry(root, (EntityLinks.aliases(root) + members.flatMap { listOf(it.text) + EntityLinks.aliases(it) }).distinctBy(EntityLinks::key),
                members, grouped[root].orEmpty().sortedWith(compareBy({ it.chapterIndex }, { it.blockIndex }, { it.startChar })))
        } + grouped[null].orEmpty().map { Entry(it, emptyList(), emptyList(), listOf(it)) }
        val key = EntityLinks.key(query)
        return all.filter { e -> key.isEmpty() || (listOf(e.record.text, e.record.note) + e.variants + e.originals.map { it.note } + e.quotes.map { quote(it) })
            .any { EntityLinks.key(it).contains(key) } }.sortedBy { EntityLinks.key(it.record.text) }
    }

    /** Plain Markdown retains the original Wikipedia attribution and pinned revision URLs. */
    fun markdown(title: String, author: String, entries: List<Entry>): String = buildString {
        append("# ").append(title).append(" — люди и понятия\n\n")
        if (author.isNotBlank()) append(author).append("\n\n")
        entries.forEach { e ->
            append("## ").append(e.record.text).append("\n\n")
            if (e.variants.isNotEmpty()) append("Варианты: ").append(e.variants.joinToString(", ")).append("\n\n")
            if (e.record.note.isNotBlank()) append(e.record.note).append("\n\n")
            e.originals.filter { it.note.isNotBlank() }.forEach { append("### Заметка: ").append(it.text).append("\n\n").append(it.note).append("\n\n") }
            e.quotes.forEach { q ->
                append("### ").append(q.chapterTitle.ifBlank { "Раздел ${q.chapterIndex + 1}" }).append("\n\n")
                append(quote(q).lineSequence().joinToString("\n") { "> $it" }).append("\n\n")
                if (q.id != e.record.id && q.note.isNotBlank()) append(q.note).append("\n\n")
            }
        }
    }
}
