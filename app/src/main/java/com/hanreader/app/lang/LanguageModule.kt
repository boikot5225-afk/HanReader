package com.hanreader.app.lang

import com.hanreader.app.BookWord
import com.hanreader.app.ScriptMode
import com.hanreader.app.epub.BookSource
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.reader.BookAnalysis
import com.hanreader.app.reader.RToken
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.isHan
import java.util.Locale

/**
 * The "socket" between the reader and a language. Everything the reader, pages, themes,
 * bookmarks, cards and backups do is language-neutral; what knows about a particular language
 * (how text splits into words, the dictionary, the line drawn above words, levels, speech)
 * sits behind [LanguageModule]. Chinese ([com.hanreader.app.reader.ZhEngine]) is the first module.
 *
 * Tokens: [TokenKind.HAN] means "a word of the language" (a tappable word), whatever the script.
 */
data class LanguageInfo(
    /** BCP 47 tag: the study language of cards and the speech voice ("zh-CN"). */
    val tag: String,
    /** The language's name in Russian, for the interface ("Китайский"). */
    val title: String,
    /** What the line above words is called ("пиньинь"); null when the language draws none. */
    val readingTitle: String?,
    /** The word-level system ("HSK"); null when the language has none yet. */
    val levelsTitle: String?,
    /** Characters that end a sentence (to cut the example sentence for a card). */
    val sentenceEnd: String,
    /** Shown when the phone has no voice for the language. */
    val voiceMissing: String,
    /** Shown when pasted text has no words of the language. */
    val noWordsMessage: String,
    /** A letter of the language: tells a text in it from other text, and a name the reader may mark. */
    val isWordChar: (Char) -> Boolean
) {
    val locale: Locale get() = Locale.forLanguageTag(tag)
}

/** Reading options a chapter is prepared with. */
data class TextOptions(
    /** Words to keep whole although the dictionary lacks them (names found in the book or marked by the reader). */
    val extra: Set<String> = emptySet(),
    /** Dictionary words the reader split: never kept whole. */
    val blocked: Set<String> = emptySet(),
    /** Chinese only: the characters to draw. Other languages ignore it. */
    val script: ScriptMode = ScriptMode.AS_BOOK
)

/**
 * One paragraph ready to draw. [text] is what the dictionary, search and cards use;
 * [shown] is what is drawn (same length, offsets shared); [tokens] cover [text] with offsets.
 */
data class PreparedText(val text: String, val shown: String, val tokens: List<RToken>)

interface LanguageModule {
    val info: LanguageInfo

    /**
     * Prepares a chapter: called once with all its paragraphs (a module may look at the whole
     * chapter first, as Chinese does to detect traditional script), returns the function that
     * prepares each paragraph.
     */
    fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText

    /** A few words to write above an unknown word; null if there is no meaning. */
    fun glossShort(word: String, reading: String? = null): String?

    /** The meaning stored on a flash card; null if there is none. */
    fun cardTranslation(word: String, reading: String? = null): String?

    // Everything below has a neutral default: a new language starts with the methods above
    // and fills these in when it has the data.

    /** The dictionary reading stored on a card (pinyin); null when the language has none. */
    fun reading(word: String): String? = null

    /** Worth suggesting for study: has a meaning and is not a proper noun. */
    fun studyable(word: String): Boolean = true

    /** A proper name (person, place, organisation) from the language's own data. */
    fun isName(word: String): Boolean = false

    /** Token ranges of a paragraph that may be a name; a range counts only if the book lists it as one. */
    fun nameCandidates(tokens: List<RToken>): List<IntRange> = emptyList()

    /** Token ranges of a paragraph that are certainly parts of a name (Chinese: next to a middle dot). */
    fun sureNames(tokens: List<RToken>): List<IntRange> = emptyList()

    /** The word levels (HSK, later A1–C2), or null when the language has none yet. */
    val levels: WordLevels? get() = null

    /**
     * [word] is not in [known] but follows from words in it (Chinese: 看 + 着, 一 + 个人).
     * Called only for words of two or more characters.
     */
    fun knownByParts(word: String, known: Set<String>): Boolean = false

    /** Words that follow from [base] and count as known with it (Chinese: 他 → 他们). */
    fun expandKnown(base: Collection<String>): Set<String> = emptySet()

    /** The form text is searched in (Chinese: simplified characters); offsets must not change. */
    fun searchForm(text: String): String = text

    /** The query is a reading (Chinese: pinyin), not text: search with [readingMatches]. */
    fun isReadingQuery(query: String): Boolean = false

    /** Character ranges of [text] whose reading matches [query]. */
    fun readingMatches(text: String, query: String): List<IntRange> = emptyList()

    /**
     * Counts every word of a book once (the book's dictionary). The default goes through
     * [chapter]; a module may do it faster or also find the book's names.
     */
    fun analyze(bookId: Long, book: BookSource, onProgress: (Int, Int) -> Unit = { _, _ -> }): BookAnalysis {
        val counts = HashMap<String, IntArray>() // [count, firstChapter]
        var tokens = 0
        for (c in book.chapters) {
            val texts = book.blocks(c.index).mapNotNull { (it as? EpubBlock.Text)?.text }
            val prepare = chapter(texts, TextOptions())
            for (t in texts) for (tok in prepare(t).tokens) {
                if (tok.kind != TokenKind.HAN) continue
                tokens++
                counts.getOrPut(tok.text) { intArrayOf(0, c.index) }[0]++
            }
            onProgress(c.index + 1, book.chapters.size)
        }
        return BookAnalysis(counts.map { (w, e) -> BookWord(bookId, w, e[0], e[1]) }, tokens)
    }
}

/** Word levels of a language, from easiest. Chinese: HSK 3.0, 1..7 (7 = 7–9). */
interface WordLevels {
    /** "HSK". */
    val name: String
    val all: List<Int>
    /** "7–9" for level 7 of HSK 3.0. */
    fun title(level: Int): String
    /** The level of a listed word; 0 when it is not on the lists. */
    fun of(word: String): Int
    /** The level of any word: listed, or guessed (e.g. from frequency); above [all] for rare words. */
    fun estimate(word: String): Int
    /** Every word known at [level]: the lists up to it plus what follows from them. */
    fun knownAt(level: Int): Set<String>
}

/** The languages the app knows. One build reads one language: [current]. */
object Languages {
    val CHINESE = LanguageInfo(
        tag = "zh-CN",
        title = "Китайский",
        readingTitle = "пиньинь",
        levelsTitle = "HSK",
        sentenceEnd = "。！？!?…；;\n",
        voiceMissing = "Нет китайского голоса. Установи его: Настройки Android → Синтез речи",
        noWordsMessage = "В тексте нет китайских иероглифов",
        isWordChar = { it.isHan() }
    )

    val current: LanguageInfo = CHINESE
}
