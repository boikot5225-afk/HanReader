package com.hanreader.app.reader

import com.hanreader.app.ScriptMode
import com.hanreader.app.epub.BookSource
import com.hanreader.app.lang.LanguageModule

internal data class PreparedChapterKey(
    val source: BookSource,
    val index: Int,
    val engine: LanguageModule,
    val names: Set<String>,
    val splits: Set<String>,
    val script: ScriptMode
)

/** Main-thread-owned LRU; never retains a whole book or an oversized chapter. */
internal class PreparedChapterCache(private val maxEntries: Int = 3, private val maxChars: Int = 200_000) {
    private val entries = LinkedHashMap<PreparedChapterKey, ChapterContent>(4, 0.75f, true)
    private var chars = 0
    operator fun get(key: PreparedChapterKey): ChapterContent? = entries[key]
    fun put(key: PreparedChapterKey, chapter: ChapterContent) {
        entries.remove(key)?.let { chars -= it.chars }
        if (chapter.chars > maxChars) return
        entries[key] = chapter
        chars += chapter.chars
        while (entries.size > maxEntries || chars > maxChars) {
            val iterator = entries.entries.iterator()
            chars -= iterator.next().value.chars
            iterator.remove()
        }
    }
    fun clear() { entries.clear(); chars = 0 }
}
