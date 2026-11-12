package com.hanreader.app.reader

import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.zh.TokenKind

/** Where the reader's own meaning of a word applies. */
enum class MeaningScope(val title: String, val about: String) {
    HERE("Здесь", "только в этом месте"),
    BOOK("В этой книге", "в этой книге"),
    EVERYWHERE("Везде", "во всех книгах")
}

/**
 * The reader's own meanings of a word in one place or in one book, kept in the same table as the
 * meanings for every book (user_meanings), under keys that cannot be a word:
 * - «@<book>|o|<chapter>:<block>:<start>» — the word (or phrase) starting at character [start] of
 *   that paragraph;
 * - «@<book>|b|<key>» — the word in the whole book, by its dictionary key (Spanish: the lemma or
 *   phrase the context chose, «limitarse», «traer cola»; Chinese: the word itself).
 * A meaning for every book stays under the word itself, as before. The nearest one wins: here, then
 * the book, then every book.
 */
object ContextMeanings {
    fun here(book: Long, chapter: Int, block: Int, start: Int) = "@$book|o|$chapter:$block:$start"
    fun inBook(book: Long, key: String) = "@$book|b|$key"

    /** The key a word on the page has in its book: what the context chose for it. */
    fun keyOf(t: RToken, engine: LanguageModule): String = when {
        engine is ZhEngine -> t.text
        t.pinyin.isNotEmpty() && t.pinyin != com.hanreader.app.es.EsEngine.IN_PHRASE -> t.pinyin
        else -> engine.lemma(t.text)
    }

    /** A key of a place or a book, not a word: such meanings are not listed as words. */
    fun isScoped(key: String) = key.startsWith("@")

    /** This chapter's meanings for one place: «<block>:<start>» -> meaning. */
    fun herein(all: Map<String, String>, book: Long, chapter: Int): Map<String, String> {
        val prefix = "@$book|o|$chapter:"
        return all.entries.filter { it.key.startsWith(prefix) }.associate { it.key.removePrefix(prefix) to it.value }
    }

    /** This book's meanings: dictionary key -> meaning. */
    fun ofBook(all: Map<String, String>, book: Long): Map<String, String> {
        val prefix = "@$book|b|"
        return all.entries.filter { it.key.startsWith(prefix) }.associate { it.key.removePrefix(prefix) to it.value }
    }

    /**
     * Adds the reader's meanings of this chapter to the hints [glossFor]: a place by «@<block>:<start>»
     * (shown even over a known word: the reader asked for it), a book meaning over each of the words
     * it is for that gets a hint ([hinted]; Chinese: a book meaning replaces the meaning of every reading).
     */
    fun addTo(glossFor: MutableMap<String, String>, chapter: ChapterContent, here: Map<String, String>, book: Map<String, String>,
              engine: LanguageModule, hinted: Set<String>) {
        chapter.blocks.forEachIndexed { bi, b ->
            val tokens = (b as? ReaderBlock.Text)?.tokens ?: return@forEachIndexed
            for (t in tokens) {
                if (t.kind != TokenKind.HAN) continue
                here["$bi:${t.start}"]?.let { glossFor["@$bi:${t.start}"] = it; continue }
                if (book.isEmpty() || t.text !in hinted || t.pinyin == com.hanreader.app.es.EsEngine.IN_PHRASE) continue
                val m = book[keyOf(t, engine)] ?: continue
                if (engine is ZhEngine) {
                    // Chinese: a book meaning replaces the meaning of every reading of the word.
                    glossFor[t.text] = m
                    if (t.pinyin.isNotEmpty()) glossFor[t.text + "|" + t.pinyin] = m
                } else glossFor[if (t.pinyin.isEmpty()) t.text else t.text + "|" + t.pinyin] = m
            }
        }
    }
}
