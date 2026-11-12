package com.hanreader.app.reader

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
 * - «@<book>|o|<chapter>:<block>:<start>» — the word starting at character [start] of that paragraph;
 * - «@<book>|b|<word>» — the word in the whole book.
 * A meaning for every book stays under the word itself, as before. The nearest one wins: here, then
 * the book, then every book. (Ported from the Spanish branch, where the key is the dictionary form.)
 */
object ContextMeanings {
    fun here(book: Long, chapter: Int, block: Int, start: Int) = "@$book|o|$chapter:$block:$start"
    fun inBook(book: Long, word: String) = "@$book|b|$word"

    /** A key of a place or a book, not a word: such meanings are not listed as words. */
    fun isScoped(key: String) = key.startsWith("@")

    /** This chapter's meanings for one place: «<block>:<start>» -> meaning. */
    fun herein(all: Map<String, String>, book: Long, chapter: Int): Map<String, String> {
        val prefix = "@$book|o|$chapter:"
        return all.entries.filter { it.key.startsWith(prefix) }.associate { it.key.removePrefix(prefix) to it.value }
    }

    /** This book's meanings: word -> meaning. */
    fun ofBook(all: Map<String, String>, book: Long): Map<String, String> {
        val prefix = "@$book|b|"
        return all.entries.filter { it.key.startsWith(prefix) }.associate { it.key.removePrefix(prefix) to it.value }
    }

    /**
     * Adds the reader's meanings of this chapter to the hints [glossFor]: a place by «@<block>:<start>»
     * (shown even over a known word: the reader asked for it), a book meaning over each of the words
     * it is for that gets a hint ([hinted]); a book meaning replaces the meaning of every reading.
     */
    fun addTo(glossFor: MutableMap<String, String>, chapter: ChapterContent, here: Map<String, String>, book: Map<String, String>, hinted: Set<String>) {
        chapter.blocks.forEachIndexed { bi, b ->
            val tokens = (b as? ReaderBlock.Text)?.tokens ?: return@forEachIndexed
            for (t in tokens) {
                if (t.kind != TokenKind.HAN) continue
                here["$bi:${t.start}"]?.let { glossFor["@$bi:${t.start}"] = it; continue }
                if (t.text !in hinted) continue
                book[t.text]?.let { m ->
                    glossFor[t.text] = m
                    if (t.pinyin.isNotEmpty()) glossFor[t.text + "|" + t.pinyin] = m
                }
            }
        }
    }
}
