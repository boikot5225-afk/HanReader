package com.hanreader.app.reader

import com.hanreader.app.ai.AiCheck
import com.hanreader.app.lang.IN_PHRASE
import com.hanreader.app.lang.LanguageModule

/** Where the meaning of one occurrence comes from. [tier] 0: chosen for this context; 1: a guess; 2: the dictionary. */
enum class MeaningSource(val label: String, val tier: Int) {
    USER("твоё значение", 0),
    AI("DeepSeek", 0),
    AI_CONFIRMED("DeepSeek: подходит", 0),
    CONTEXT("по контексту", 0),
    PROPOSED("DeepSeek не уверен", 1),
    DICTIONARY("словарь", 2);

    val contextual: Boolean get() = tier == 0
}

data class PlaceMeaning(val meaning: String, val source: MeaningSource)

/** One occurrence of a word: paragraph [block], token index [token], end of the word or phrase [end]. */
data class Place(val block: Int, val token: Int, val end: Int)

/** The occurrences of a word in the chapter that share a meaning (and, in Chinese, a reading). */
data class MeaningGroup(
    val meaning: String,
    val reading: String,
    val source: MeaningSource,
    val count: Int,
    /** The occurrence that represents the group: the first one with the strongest source. */
    val block: Int,
    val start: Int,
    val end: Int,
    val sentence: String
)

/**
 * The meaning of each occurrence of a word in a chapter, as in the text, not as in the dictionary:
 * the reader's own meaning (here, in this book, everywhere), then an accepted DeepSeek answer or a
 * DeepSeek confirmation of the hint, then the local context rule, then a DeepSeek guess, and only
 * then the dictionary. [chapter] is the chapter as drawn (with the readings DeepSeek chose);
 * [aiRows] the checks still valid for it.
 */
class ChapterMeanings(
    private val book: Long,
    private val chapter: ChapterContent,
    private val engine: LanguageModule,
    private val userMeanings: Map<String, String>,
    aiRows: List<AiCheck>
) {
    private val zh = engine is ZhEngine
    private val rows: Map<Triple<Int, Int, Int>, AiCheck> = aiRows.asSequence()
        .filter { it.chapter == chapter.index && it.state != "rejected" }
        .groupBy { Triple(it.block, it.start, it.end) }
        .mapValues { (_, list) -> list.minBy { when (it.state) { "accepted" -> 0; "checked" -> 1; else -> 2 } } }

    /** The meaning of token [t] of paragraph [block]; [end] past [t] for a phrase that starts with it. */
    fun at(block: Int, t: RToken, end: Int = t.end): PlaceMeaning {
        // A word inside a phrase (dar in «dar cuenta de»), asked about as a word: the word's own meaning.
        val inPhrase = !zh && end == t.end && (t.pinyin == IN_PHRASE || ' ' in t.pinyin)
        user(block, t, inPhrase)?.let { return PlaceMeaning(it, MeaningSource.USER) }
        val row = rows[Triple(block, t.start, end)]
        when (row?.state) {
            "accepted" -> return PlaceMeaning(row.translation?.takeIf { it.isNotBlank() } ?: short(t, end).orEmpty(), MeaningSource.AI)
            "checked" -> row.base.takeIf { it.isNotBlank() }?.let { return PlaceMeaning(it, MeaningSource.AI_CONFIRMED) }
        }
        if (!inPhrase) t.contextMeaning?.takeIf { it.isNotBlank() }?.let { return PlaceMeaning(it, MeaningSource.CONTEXT) }
        // A reading DeepSeek chose for a longer expression (借钱不还: huán): the meaning of that reading.
        if (zh && t.glossReading != null && t.glossReading != t.pinyin)
            engine.glossShort(t.text, t.pinyin)?.let { return PlaceMeaning(it, MeaningSource.AI) }
        if (row?.state == "proposed") row.translation?.takeIf { it.isNotBlank() }?.let { return PlaceMeaning(it, MeaningSource.PROPOSED) }
        return PlaceMeaning(dictionary(t, end, inPhrase).orEmpty(), MeaningSource.DICTIONARY)
    }

    /** [places] grouped by meaning: contextual meanings first, then guesses, then the dictionary; more frequent first. */
    fun groups(places: List<Place>): List<MeaningGroup> {
        val acc = LinkedHashMap<Pair<String, String>, MeaningGroup>()
        for (p in places) {
            val b = chapter.blocks.getOrNull(p.block) as? ReaderBlock.Text ?: continue
            val t = b.tokens.getOrNull(p.token) ?: continue
            val m = at(p.block, t, p.end)
            val reading = if (zh) t.pinyin else ""
            val key = m.meaning.trim().lowercase() to reading
            val g = acc[key]
            acc[key] = when {
                g == null -> MeaningGroup(m.meaning.trim(), reading, m.source, 1, p.block, t.start, p.end, ChapterBuilder.sentence(b.text, t.start, p.end))
                m.source.ordinal < g.source.ordinal ->
                    g.copy(source = m.source, count = g.count + 1, block = p.block, start = t.start, end = p.end, sentence = ChapterBuilder.sentence(b.text, t.start, p.end))
                else -> g.copy(count = g.count + 1)
            }
        }
        return acc.values.sortedWith(compareBy<MeaningGroup> { it.source.tier }.thenByDescending { it.count }.thenBy { it.source.ordinal })
    }

    private fun user(block: Int, t: RToken, inPhrase: Boolean): String? {
        val lemma = engine.lemma(t.text)
        val keys = if (inPhrase) listOf(lemma) else {
            val key = ContextMeanings.keyOf(t, engine)
            listOf(ContextMeanings.here(book, chapter.index, block, t.start), ContextMeanings.inBook(book, key), t.text, key, lemma)
        }
        return keys.firstNotNullOfOrNull { k -> userMeanings[k]?.trim()?.takeIf { it.isNotEmpty() } }
    }

    private fun short(t: RToken, end: Int): String? =
        if (zh) engine.glossShort(t.text, t.pinyin.takeIf { it.isNotBlank() })
        else if (end > t.end) engine.glossShort(t.text, t.pinyin) else engine.glossShort(t)

    private fun dictionary(t: RToken, end: Int, inPhrase: Boolean): String? {
        val reading = t.pinyin.takeIf { it.isNotBlank() && it != IN_PHRASE }
        return when {
            zh -> engine.cardTranslation(t.text, reading) ?: engine.glossShort(t.text, reading)
            end > t.end -> engine.glossShort(t.text, t.pinyin)
            inPhrase -> engine.lemma(t.text).let { engine.cardTranslation(it) ?: engine.glossShort(it) }
            else -> engine.cardTranslation(t.text, reading) ?: engine.glossShort(t.text, reading)
        }
    }
}
