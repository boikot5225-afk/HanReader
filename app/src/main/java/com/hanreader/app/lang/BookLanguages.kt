package com.hanreader.app.lang

import android.content.Context
import com.hanreader.app.epub.BookSource
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.zh.isHan

/**
 * The language of each book, kept in the app's settings (book id -> tag), so the database schema
 * stays as it is. A book without a record is Chinese: every book from before languages existed.
 * The library shows only the books of [Languages.current].
 */
object BookLanguages {
    private const val PREFS = "book_languages"

    /** Bumped on every change, so the library refilters (a book's language is saved after its row). */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun of(context: Context, bookId: Long): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(bookId.toString(), null) ?: Languages.CHINESE.tag

    fun set(context: Context, bookId: Long, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(bookId.toString(), tag).apply()
        changes.value++
    }

    /** After restoring a backup: exactly these languages (book id -> tag); other books are Chinese. */
    fun replaceAll(context: Context, languages: Map<Long, String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply {
            languages.forEach { (id, tag) -> putString(id.toString(), tag) }
        }.apply()
        changes.value++
    }

    fun remove(context: Context, bookId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(bookId.toString()).apply()
    }

    /** Chinese when a good share of the letters in the first chapters are Chinese characters, else Spanish. */
    fun detect(book: BookSource): LanguageInfo {
        var han = 0
        var letters = 0
        for (c in book.chapters.take(10)) {
            for (b in book.blocks(c.index)) {
                val t = (b as? EpubBlock.Text)?.text ?: continue
                for (ch in t) if (ch.isLetter()) { letters++; if (ch.isHan()) han++ }
                if (letters > 20_000) break
            }
            if (letters > 20_000) break
        }
        return if (letters > 0 && han * 3 >= letters) Languages.CHINESE else Languages.SPANISH
    }
}

/**
 * Reading time, pages and reviews by day are kept per language in the same table (no schema
 * change): Chinese days under the date itself (20261109, as before), another language's days under
 * the date plus its offset (Spanish: 1 020 261 109). Statistics show the current language's days.
 */
object LanguageDays {
    private const val OFFSET = 1_000_000_000

    private fun index(l: LanguageInfo = Languages.current): Int = Languages.ALL.indexOf(l).coerceAtLeast(0)

    /** The stored key of [day] for the current language. */
    fun key(day: Int): Int = day + index() * OFFSET

    /** The current language's rows, with plain dates. */
    fun own(rows: List<com.hanreader.app.DailyActivity>): List<com.hanreader.app.DailyActivity> {
        val i = index()
        return rows.filter { it.day / OFFSET == i }.map { it.copy(day = it.day - i * OFFSET) }
    }
}
