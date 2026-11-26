package com.hanreader.app.reader

import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.zh.TokenKind

/** A local, read-only forecast. Selecting words does not change known marks or SRS progress. */
data class ChapterPreparation(
    val coverage: KnownCoverage,
    val unknownOccurrences: Map<String, Int>,
    private val unknownForms: Map<String, Int> = unknownOccurrences,
    private val known: Set<String> = emptySet(),
    private val engine: LanguageModule? = null
) {
    fun afterLearning(words: Collection<String>): KnownCoverage {
        val selected = words.toSet()
        if (selected.isEmpty()) return coverage
        // Use the reader's actual known rules: ambiguous forms, Chinese expansions and explicit
        // «Не знаю» marks must behave exactly as after marking the selected dictionary words known.
        val projected = KnownMatcher(KnownWords(known + selected,
            (known as? KnownWords)?.unknown.orEmpty() - selected), engine)
        var added = 0
        val remaining = HashSet<String>()
        for ((form, count) in unknownForms) {
            if (projected.isKnown(form)) added += count
            else remaining += engine?.lemma(form) ?: form
        }
        return coverage.copy(known = coverage.known + added, unknownDistinct = remaining.size)
    }

    companion object {
        fun of(chapter: ChapterContent, known: Set<String>, names: Set<String>, engine: LanguageModule): ChapterPreparation {
            val matcher = KnownMatcher(known, engine)
            var total = 0
            var familiar = 0
            val unknown = HashMap<String, Int>()
            val forms = HashMap<String, Int>()
            for (block in chapter.blocks) {
                if (block !is ReaderBlock.Text) continue
                val nameTokens = VocabularyStats.nameTokens(block, names, engine)
                for ((index, token) in block.tokens.withIndex()) {
                    if (token.kind != TokenKind.HAN || index in nameTokens || token.text in names ||
                        VocabularyStats.isName(token.text, engine)) continue
                    total++
                    if (matcher.isKnown(token.text)) familiar++
                    else {
                        val lemma = engine.lemma(token.text)
                        unknown[lemma] = (unknown[lemma] ?: 0) + 1
                        forms[token.text] = (forms[token.text] ?: 0) + 1
                    }
                }
            }
            return ChapterPreparation(KnownCoverage(total, familiar, unknown.size), unknown, forms, known, engine)
        }
    }
}
