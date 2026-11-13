package com.hanreader.app.lang

import com.hanreader.app.reader.RToken

/**
 * What the reader needs from a language written with an alphabet and spaces (Spanish now, English
 * next): the word card, phrases, a verb's forms. Chinese is not one: its card shows characters and
 * readings. The shared screens ask for [AlphabeticModule], never for a particular language.
 */

/** [RToken.pinyin] of a word inside a phrase after its first word: the phrase's hint is over the first word only. */
const val IN_PHRASE = "~"

/** A dictionary word: [ru] Russian senses, [en] English ones (from the English Wiktionary). */
data class LexEntry(
    val lemma: String,
    /** Part of speech in Russian: «гл.», «сущ.», «прил.», «имя». */
    val pos: String,
    val ru: List<String>,
    val en: List<String>,
    /** The Russian meaning is a machine translation of the English one (not the Russian Wiktionary). */
    val machine: Boolean = false,
    /** Frequency rank: 1 = most frequent, 0 = not in the list. */
    val rank: Int = 0,
    /** The main meaning in a few words, for the note over the word. */
    val short: String = "",
    /** Gender of a noun: «м», «ж», «мж» (both); empty for other words and languages without gender. */
    val gender: String = "",
    /** The word as other parts of speech (haber: the noun «имущество» beside the verb), each with its own senses. */
    val alts: List<LexEntry> = emptyList(),
    /** Names this reading among the readings of a word: the lemma, for another part of speech «haber/сущ.». */
    val key: String = lemma
) {
    /** «сущ., ж. р.», «сущ., м. и ж. р.», «гл.». */
    val posLabel: String get() = when (gender) {
        "м" -> "$pos, м. р."
        "ж" -> "$pos, ж. р."
        "мж" -> "$pos, м. и ж. р."
        else -> pos
    }

    val isName: Boolean get() = pos == "имя"
    val isVerb: Boolean get() = pos == "гл."
}

/** A word on the page read as a dictionary word: [form] as it stands, [grammar] what the form is («3 л. ед. ч., претерит»). */
data class LexAnalysis(val form: String, val entry: LexEntry, val grammar: String)

/** What a verb form is and why it is used, for the word card. */
data class FormNote(
    /** Tense or mood in the language and in Russian: «Pretérito indefinido — простое прошедшее». */
    val tense: String,
    /** «3-е лицо ед. ч. (él, ella, usted)»; null when the form has no person. */
    val person: String?,
    /** When it is used, in a sentence or two. */
    val explanation: String,
    /** The words of a compound form on the page: «había venido». Null for a single word. */
    val construction: String? = null,
    /** Pronouns attached to the verb: «me lo». */
    val pronouns: String? = null
)

/** A verb's forms ready to show: forms without a person, then each tense with its persons. */
data class Conjugation(
    /** «Gerundio: hablando», «Participio: hablado». */
    val nonFinite: List<String>,
    /** Tense title -> (person -> forms). */
    val tenses: List<Pair<String, List<Pair<String, String>>>>
)

interface AlphabeticModule : LanguageModule {
    /** The readings of the selection [a]..[b] for the card, the one the context chose first. */
    fun cardAnalyses(tokens: List<RToken>, text: String, a: Int, b: Int): List<LexAnalysis>

    /** The word the cards and «Знаю» are about, given the card's readings (Spanish: limitarse → limitar). */
    fun cardLemma(analyses: List<LexAnalysis>): String? = analyses.firstOrNull()?.entry?.lemma

    /** The Russian senses of [entry] in the order to show. */
    fun senses(entry: LexEntry): List<String> = entry.ru

    /** The dictionary phrase that token [index] is part of (sin embargo, look up), as a token range. */
    fun phraseAt(tokens: List<RToken>, text: String, index: Int): IntRange?

    /** The dictionary form of the phrase on tokens [a]..[b] (me di cuenta → darse cuenta), or null. */
    fun phraseKey(tokens: List<RToken>, text: String, a: Int, b: Int): String?

    /** What the verb form at token [index] is and why it is used; null when not a verb or unknown. */
    fun formNote(tokens: List<RToken>, index: Int, analyses: List<LexAnalysis>): FormNote? = null

    /** The whole conjugation of verb [lemma]; null when the language has no table for it. Slow: call off the main thread. */
    fun conjugationTable(lemma: String): Conjugation? = null
}
