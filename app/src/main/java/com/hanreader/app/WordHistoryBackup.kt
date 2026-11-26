package com.hanreader.app

import com.hanreader.app.reader.ReadingFragment
import com.hanreader.app.reader.WordEncounter
import com.hanreader.app.reader.WordHistory
import org.json.JSONArray
import org.json.JSONObject

data class WordHistoryBackup(val fragments: List<ReadingFragment>, val words: List<WordEncounter>, val enabled: Boolean = true)

/** Validate the complete history graph before any destructive restore, including direct callers. */
internal object WordHistoryBackupFormat {
    private fun invalid(): Nothing = throw BackupFormatException("Файл копии повреждён: неверная история слов")
    private fun JSONObject.text(key: String) = get(key) as? String ?: invalid()
    private fun JSONObject.long(key: String): Long = when (val v = get(key)) {
        is Int -> v.toLong()
        is Long -> v
        else -> invalid()
    }
    private fun JSONObject.int(key: String): Int = long(key).let { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) invalid() else it.toInt() }

    fun encode(history: WordHistoryBackup): JSONObject = JSONObject().apply {
        put("enabled", history.enabled)
        put("fragments", JSONArray().apply { history.fragments.forEach { f -> put(JSONObject().apply {
            put("id", f.id); put("sourceKey", f.sourceKey); put("bookId", f.bookId ?: JSONObject.NULL)
            put("title", f.title); put("chapterTitle", f.chapterTitle); put("chapter", f.chapter)
            put("block", f.block); put("textStart", f.textStart); put("text", f.text)
        }) } })
        put("words", JSONArray().apply { history.words.forEach { w -> put(JSONObject().apply {
            put("id", w.id); put("fragmentId", w.fragmentId); put("language", w.language); put("lemma", w.lemma)
            put("original", w.original); put("start", w.start); put("end", w.end)
            put("firstSeen", w.firstSeen); put("lookups", w.lookups)
        }) } })
    }

    fun decode(json: JSONObject): WordHistoryBackup {
        val enabled = json.get("enabled") as? Boolean ?: invalid()
        val fragments = json.getJSONArray("fragments")
        val words = json.getJSONArray("words")
        if (fragments.length() > 100_000 || words.length() > 1_000_000) invalid()
        return WordHistoryBackup((0 until fragments.length()).map { i -> fragments.getJSONObject(i).run {
            ReadingFragment(text("id"), text("sourceKey"), if (get("bookId") == JSONObject.NULL) null else long("bookId"),
                text("title"), text("chapterTitle"), int("chapter"), int("block"), int("textStart"), text("text"))
        } }, (0 until words.length()).map { i -> words.getJSONObject(i).run {
            WordEncounter(text("id"), text("fragmentId"), text("language"), text("lemma"), text("original"),
                int("start"), int("end"), long("firstSeen"), int("lookups"))
        } }, enabled)
    }

    fun validate(history: WordHistoryBackup?, library: LibraryBackup?) {
        if (history == null) return
        if (library == null) invalid()
        if (history.fragments.size > 100_000 || history.words.size > 1_000_000) invalid()
        val hash = Regex("[a-f0-9]{64}")
        val wiki = Regex("wiki:(es|en|zh):[1-9][0-9]*")
        val books = library?.books?.associateBy { it.id }.orEmpty()
        val fragments = HashMap<String, ReadingFragment>()
        for (f in history.fragments) {
            if (!hash.matches(f.id) || fragments.put(f.id, f) != null || f.chapter < 0 || f.block < 0 ||
                f.textStart < 0 || f.textStart % 8000 != 0 || f.text.isEmpty() || f.text.length > 8200 ||
                f.textStart.toLong() + f.text.length > Int.MAX_VALUE || f.title.length > 4000 || f.chapterTitle.length > 4000) invalid()
            if (f.bookId != null) {
                val book = books[f.bookId] ?: invalid()
                if (f.sourceKey != "book:${f.bookId}" || f.chapter >= book.chapterCount) invalid()
            } else if (!wiki.matches(f.sourceKey) || f.chapter != 0) invalid()
        }
        val ids = HashSet<String>()
        val languages = com.hanreader.app.lang.Languages.ALL.mapTo(HashSet()) { it.tag }
        for (w in history.words) {
            val f = fragments[w.fragmentId] ?: invalid()
            val start = w.start.toLong() - f.textStart
            val end = w.end.toLong() - f.textStart
            if (!hash.matches(w.id) || !ids.add(w.id) || w.language !in languages ||
                w.lemma.isBlank() || w.lemma.length > 180 || w.original.isBlank() || w.original.length > 180 ||
                start < 0 || end <= start || end > f.text.length || w.firstSeen < 0 || w.lookups < 0) invalid()
            if (f.bookId == null && f.sourceKey.split(':')[1] != w.language.substringBefore('-')) invalid()
            if (f.text.substring(start.toInt(), end.toInt()) != w.original ||
                w.id != WordHistory.wordId(w.fragmentId, w.start, w.end, w.lemma)) invalid()
        }
    }
}
