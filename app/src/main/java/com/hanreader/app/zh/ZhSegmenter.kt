package com.hanreader.app.zh

import kotlin.math.ln

enum class TokenKind { HAN, OTHER, SPACE, PUNCT }

/** A piece of text. [start] and [end] are offsets into the source string; [entry] is set for dictionary words. */
data class ZhToken(
    val text: String,
    val start: Int,
    val end: Int,
    val kind: TokenKind,
    val entry: ZhEntry? = null
)

fun Char.isHan(): Boolean {
    val c = code
    return (c in 0x4E00..0x9FFF) || (c in 0x3400..0x4DBF) || (c in 0xF900..0xFAFF) || c == 0x3007
}

/**
 * Dictionary segmentation of Chinese text, the same model as Jieba without its HMM:
 * for each run of Han characters every lexicon word starting at each position is a
 * candidate edge, and dynamic programming picks the most probable path, where a word's
 * probability is its Jieba count over the total. CC-CEDICT words Jieba lacks get a small
 * count, characters absent from both get a tiny one. Number + unit runs ("二十六日") stay together.
 */
class ZhSegmenter(private val dict: ZhDictionary) {

    /** [extra]: words to keep whole although the lexicon lacks them (names found in the book or marked by the reader). */
    fun segment(text: String, extra: Set<String> = emptySet()): List<ZhToken> {
        val out = ArrayList<ZhToken>(text.length / 2 + 4)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                // Characters outside the basic plane are two Java chars (a surrogate pair): never cut them.
                Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    val cp = text.codePointAt(i)
                    if (cp in 0x20000..0x3FFFF) {
                        // A rare character (CJK extensions B and later): a word of its own, tappable.
                        out += ZhToken(text.substring(i, i + 2), i, i + 2, TokenKind.HAN)
                        i += 2
                    } else {
                        // An emoji with its modifiers and joined parts (skin tone, 👨‍👩‍👧): one sign.
                        val j = emojiEnd(text, i)
                        out += ZhToken(text.substring(i, j), i, j, TokenKind.PUNCT)
                        i = j
                    }
                }
                ch.isHan() -> {
                    var j = i
                    while (j < text.length && text[j].isHan()) j++
                    segmentHan(text, i, j, out, extra)
                    i = j
                }
                ch.isWhitespace() -> {
                    var j = i
                    while (j < text.length && text[j].isWhitespace()) j++
                    out += ZhToken(text.substring(i, j), i, j, TokenKind.SPACE)
                    i = j
                }
                ch.isLetterOrDigit() -> {
                    var j = i
                    while (j < text.length && !text[j].isHan() && text[j].isLetterOrDigit()) j++
                    out += ZhToken(text.substring(i, j), i, j, TokenKind.OTHER)
                    i = j
                }
                else -> {
                    out += ZhToken(ch.toString(), i, i + 1, TokenKind.PUNCT)
                    i++
                }
            }
        }
        return out
    }

    /** The end of the emoji sequence starting at [from]: pairs joined by ZWJ, variation selectors, skin tones. */
    private fun emojiEnd(text: String, from: Int): Int {
        var j = from + Character.charCount(text.codePointAt(from))
        while (j < text.length) {
            val c = text.codePointAt(j)
            val joins = c == 0x200D || c == 0xFE0F || c in 0x1F3FB..0x1F3FF || c in 0xE0020..0xE007F
            val afterZwj = text[j - 1] == '\u200D' && Character.isSupplementaryCodePoint(c)
            if (!joins && !afterZwj) break
            j += Character.charCount(c)
        }
        return j
    }

    private fun segmentHan(text: String, from: Int, to: Int, out: MutableList<ZhToken>, extra: Set<String>) {
        val n = to - from
        val best = DoubleArray(n + 1) { Double.MAX_VALUE }
        val back = IntArray(n + 1)
        best[0] = 0.0
        val maxLen = dict.maxWordLength.coerceAtMost(MAX_WORD)
        for (i in 0 until n) {
            if (best[i] == Double.MAX_VALUE) continue
            val base = from + i
            // Single character is always possible.
            relax(best, back, i, 1, cost(text.substring(base, base + 1)))
            val limit = minOf(maxLen, n - i)
            for (len in 2..limit) {
                val w = text.substring(base, base + len)
                if (dict.contains(w)) relax(best, back, i, len, cost(w))
            }
            if (extra.isNotEmpty()) {
                for (len in 2..minOf(MAX_WORD, n - i)) {
                    if (text.substring(base, base + len) in extra) relax(best, back, i, len, extraCost)
                }
            }
            val num = numberRun(text, base, to)
            if (num >= 2) relax(best, back, i, num, numberCost)
        }
        val cuts = ArrayList<Int>()
        var k = n
        while (k > 0) {
            cuts += k
            k -= back[k]
        }
        var start = 0
        for (idx in cuts.indices.reversed()) {
            val end = cuts[idx]
            val s = from + start
            val e = from + end
            val w = text.substring(s, e)
            out += ZhToken(w, s, e, TokenKind.HAN, dict.lookup(w))
            start = end
        }
    }

    private fun relax(best: DoubleArray, back: IntArray, i: Int, len: Int, c: Double) {
        val v = best[i] + c
        if (v < best[i + len]) {
            best[i + len] = v
            back[i + len] = len
        }
    }

    private val logTotal = ln(dict.totalFreq.toDouble().coerceAtLeast(1.0))

    /** -log P(word). */
    private fun cost(word: String): Double {
        val f = dict.freq(word)
        val count = when {
            f > 0 -> f.toDouble()
            f == 0 -> CEDICT_ONLY_FREQ
            else -> UNKNOWN_FREQ
        }
        return logTotal - ln(count)
    }

    private val numberCost = logTotal - ln(NUMBER_FREQ)

    /** A name kept whole costs like a fairly common word, so it beats splitting into characters. */
    private val extraCost = logTotal - ln(EXTRA_FREQ)

    /** Length of a Chinese numeral run plus an optional unit at [start], or 0. */
    private fun numberRun(text: String, start: Int, end: Int): Int {
        var j = start
        while (j < end && text[j] in NUMERALS) j++
        val digits = j - start
        if (digits == 0) return 0
        if (j < end && text[j] in UNITS) j++
        val len = j - start
        return if (digits >= 2 || len >= 2) len else 0
    }

    companion object {
        private const val MAX_WORD = 8
        private const val CEDICT_ONLY_FREQ = 3.0
        private const val UNKNOWN_FREQ = 0.5
        private const val NUMBER_FREQ = 200.0
        private const val EXTRA_FREQ = 5_000.0
        private const val NUMERALS = "零〇一二三四五六七八九十百千万亿两"
        private const val UNITS = "年月日号点分秒岁"
    }
}
