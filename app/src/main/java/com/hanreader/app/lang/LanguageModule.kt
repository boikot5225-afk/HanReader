package com.hanreader.app.lang

import com.hanreader.app.ScriptMode
import com.hanreader.app.reader.RToken
import java.util.Locale

/**
 * The "socket" between the reader and a language. Everything the reader, pages, themes,
 * bookmarks, cards and backups do is language-neutral; what knows about a particular language
 * (how text splits into words, the dictionary, the line drawn above words, levels, speech)
 * sits behind [LanguageModule]. Chinese ([com.hanreader.app.reader.ZhEngine]) is the first module.
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
    val voiceMissing: String
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
}

/** The languages the app knows. One build reads one language: [current]. */
object Languages {
    val CHINESE = LanguageInfo(
        tag = "zh-CN",
        title = "Китайский",
        readingTitle = "пиньинь",
        levelsTitle = "HSK",
        sentenceEnd = "。！？!?…；;\n",
        voiceMissing = "Нет китайского голоса. Установи его: Настройки Android → Синтез речи"
    )

    val current: LanguageInfo = CHINESE
}
