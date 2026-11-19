package com.hanreader.app.reader

import com.hanreader.app.Highlight
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

/** Aliases belong to a book-wide note. Original notes are never deleted when two records link. */
object EntityLinks {
    fun key(value: String) = Normalizer.normalize(value.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    fun parse(json: String): List<String> {
        val array = JSONArray(json); require(array.length() <= 32)
        return List(array.length()) { i ->
            val value = array.get(i); require(value is String)
            value.trim().also { require(it.isNotBlank() && it.length <= 200 && it.any(Char::isLetter) && !it.any(Char::isISOControl)) }
        }.distinctBy(::key)
    }
    fun encode(values: List<String>): String = JSONArray(values.distinctBy(::key)).toString().also { parse(it) }
    fun aliases(h: Highlight) = runCatching { parse(h.aliasesJson) }.getOrDefault(emptyList())
    fun blocked(h: Highlight) = runCatching { parse(h.blockedAliasesJson) }.getOrDefault(emptyList())

    private class Index(val entries: List<Highlight>) {
        val names = entries.associateWith { aliases(it) }
        val bans = entries.associateWith { blocked(it).map(::key).toSet() }
        private val owners = entries.flatMap { h -> names.getValue(h).map { (h.bookId to key(it)) to h } }.groupBy({ it.first }, { it.second })
        private val byId = entries.filter { it.id > 0 }.associateBy { it.bookId to it.id }
        private val resolved = HashMap<Highlight, Highlight?>()
        @Synchronized fun root(entry: Highlight): Highlight? {
            val initial = if (names.containsKey(entry)) entry else byId[entry.bookId to entry.id] ?: entry
            if (resolved.containsKey(initial)) return resolved[initial]
            var current = initial
            val seen = HashSet<Highlight>()
            val path = ArrayList<Highlight>()
            var result: Highlight? = null
            while (seen.add(current)) {
                if (resolved.containsKey(current)) { result = resolved[current]; break }
                path += current
                val next = owners[current.bookId to key(current.text)].orEmpty().filter { it != current }
                if (next.isEmpty()) { result = current; break }
                if (next.size != 1) break
                current = next.single()
            }
            path.forEach { resolved[it] = result }
            return result
        }
        val byNamePart by lazy {
            entries.flatMap { h ->
                val words = Regex("[\\p{Lu}][\\p{L}\\p{M}'’-]+").findAll(h.text).map { it.value }.toList()
                if (words.size < 2) emptyList() else words.map { (h.bookId to key(it)) to root(h) }
            }.groupBy({ it.first }, { it.second })
        }
    }
    private val indices = object : LinkedHashMap<List<Highlight>, Index>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Highlight>, Index>?) = size > 2
    }
    private fun index(entries: List<Highlight>): Index = synchronized(indices) {
        indices[entries] ?: entries.toList().let { snapshot -> Index(snapshot).also { indices[snapshot] = it } }
    }

    /** A canonical spelling can itself link to another entry. Resolve by spelling, not Room IDs,
     * because highlight IDs are regenerated when a backup is restored. */
    fun root(h: Highlight, entries: List<Highlight>): Highlight? = index(entries).root(h)
    fun roots(entries: List<Highlight>) = entries.mapNotNull { root(it, entries) }.distinct()
    fun validate(entries: List<Highlight>) {
        val claims = entries.flatMap { h -> parse(h.aliasesJson).map { (h.bookId to key(it)) } }
        require(claims.distinct().size == claims.size)
        entries.forEach { h ->
            val names = parse(h.aliasesJson); parse(h.blockedAliasesJson)
            require(names.none { key(it) == key(h.text) })
            if (names.isNotEmpty() || blocked(h).isNotEmpty()) require(h.chapterIndex == Highlights.BOOK_WIDE)
            require(root(h, entries) != null)
        }
    }

    data class Mention(val entry: Highlight, val tokens: IntRange, val spelling: String, val automatic: Boolean)
    private fun ranges(block: ReaderBlock.Text, spelling: String): List<IntRange> {
        val pattern = spelling.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        if (pattern.isEmpty()) return emptyList()
        fun cjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
        return Regex(pattern, RegexOption.IGNORE_CASE).findAll(block.text).mapNotNull { m ->
            val start = m.range.first; val end = m.range.last + 1
            val left = start == 0 || !block.text[start - 1].isLetterOrDigit() || cjk(spelling.first())
            val right = end == block.text.length || !block.text[end].isLetterOrDigit() || cjk(spelling.last())
            if (left && right) Highlights.range(block, start, end) else null
        }.toList()
    }
    private val person = Regex("^[\\p{Lu}][\\p{L}\\p{M}'’-]+\\s+[\\p{Lu}][\\p{L}\\p{M}'’-]{2,}$")
    private val before = Regex("([\\p{Lu}][\\p{L}\\p{M}'’-]+)(?:\\s+(?:de|del|la|las|los|van|von|da|dos|di)){0,3}\\s+$")
    private val titles = setOf("el", "la", "los", "las", "cuando", "pero", "entonces", "señor", "señora", "don", "doña", "the", "mr", "mrs", "dr", "sir")
    private fun parts(h: Highlight): List<String>? = h.text.trim().takeIf { person.matches(it) }?.split(Regex("\\s+"))
    private fun foreignPrefix(block: ReaderBlock.Text, at: IntRange, full: List<String>): Boolean {
        val start = block.tokens[at.first].start
        val prefix = before.find(block.text.substring(0, start))?.groupValues?.get(1) ?: return false
        return key(prefix) != key(full.first()) && key(prefix) !in titles
    }
    fun mentions(block: ReaderBlock.Text, entries: List<Highlight>): List<Mention> {
        if (entries.isEmpty()) return emptyList()
        val index = index(entries)
        val out = ArrayList<Mention>()
        for (h in entries) {
            val owner = index.root(h) ?: continue
            for (name in (listOf(h.text) + index.names[h].orEmpty()).distinctBy(::key)) {
                for (range in ranges(block, name)) {
                    // A short surname must never steal the end of another person's full name.
                    val full = parts(owner)
                    if (!name.any(Char::isWhitespace) && full != null && key(name) == key(full.last()) && foreignPrefix(block, range, full)) continue
                    out += Mention(owner, range, name, false)
                }
            }
            val full = parts(h) ?: continue
            val surname = full.last()
            val fullRanges = ranges(block, h.text)
            if (key(surname) in index.bans[owner].orEmpty() || fullRanges.isEmpty()) continue
            if (index.byNamePart[h.bookId to key(surname)].orEmpty().any { it != owner }) continue
            val short = ranges(block, surname)
            if (short.any { foreignPrefix(block, it, full) }) continue
            for (range in short) {
                if (block.tokens[range.first].start < block.tokens[fullRanges.first().last].end) continue
                if (block.tokens[range.first].text.firstOrNull()?.isUpperCase() != true) continue
                out += Mention(owner, range, surname, true)
            }
        }
        return out
    }
    fun at(entries: List<Highlight>, block: ReaderBlock.Text, start: Int, end: Int): Mention? {
        if (start < 0 || end <= start || end > block.text.length) return null
        val covered = mentions(block, entries).filter { m ->
            val first = block.tokens[m.tokens.first].start; val last = block.tokens[m.tokens.last].end
            start >= first && end <= last
        }
        if (covered.isEmpty()) return null
        // A full name outranks a surname inside it. Conflicting equal matches stay unresolved.
        val longest = covered.maxOf { block.tokens[it.tokens.last].end - block.tokens[it.tokens.first].start }
        val longestMatches = covered.filter { block.tokens[it.tokens.last].end - block.tokens[it.tokens.first].start == longest }
        val explicit = longestMatches.filterNot { it.automatic }
        val best = explicit.ifEmpty { longestMatches }
        if (best.map { it.entry }.distinct().size != 1) return null
        return best.sortedBy { it.automatic }.first()
    }
}
