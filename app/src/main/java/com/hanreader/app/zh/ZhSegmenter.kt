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
    /** [blocked]: dictionary words the reader split («把头» is 把 + 头 in their books): never kept whole. */
    fun segment(text: String, extra: Set<String> = emptySet(), blocked: Set<String> = emptySet()): List<ZhToken> {
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
                    segmentHan(text, i, j, out, extra, blocked)
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
        return if (blocked.isEmpty() && extra.isEmpty()) refine(out) else refine(out, extra)
    }

    /**
     * Context rules over the most probable path: words that are right on their own but wrong
     * in this place. Word counts cannot see the neighbours; these rules do.
     *  - 把门 / 把手 / 把头 before a verb are 把 + noun (他把门关上), not «сторожить ворота», «ручка».
     *  - 才能 before a verb is 才 + 能 (才能来), not «талант» (他的才能).
     *  - 的话 is «если» only before a comma; elsewhere it is 的 + 话 (你说的话).
     *  - 就要 is «вот-вот» only with a 了 later in the clause; else 就 + 要.
     *  - a numeral with a measure word is two words (一个, 两只, 几张), as with any other numeral;
     *    这本书 is 这 + 本 + 书.
     * Words the reader keeps whole ([extra]) are left alone.
     */
    private fun refine(tokens: List<ZhToken>, extra: Set<String> = emptySet()): List<ZhToken> {
        var out: ArrayList<ZhToken>? = null
        for ((i, t) in tokens.withIndex()) {
            val cut = if (t.kind == TokenKind.HAN && t.text.length in 2..3 && t.text !in extra) splitAt(tokens, i) else 0
            if (cut == 0) { out?.add(t); continue }
            if (out == null) out = ArrayList<ZhToken>(tokens.size + 8).apply { addAll(tokens.subList(0, i)) }
            val a = t.text.substring(0, cut)
            val b = t.text.substring(cut)
            out.add(ZhToken(a, t.start, t.start + cut, TokenKind.HAN, dict.lookup(a)))
            out.add(ZhToken(b, t.start + cut, t.end, TokenKind.HAN, dict.lookup(b)))
        }
        return out ?: tokens
    }

    /** Where token [i] is cut in two by a context rule, or 0. */
    private fun splitAt(tokens: List<ZhToken>, i: Int): Int {
        val w = tokens[i].text
        val prev = tokens.getOrNull(i - 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        val next = tokens.getOrNull(i + 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        return when {
            w in BA_NOUNS ->
                if (next != null && next[0] !in "的是很也都坏" && (prev == null || prev.last() !in "的个这那只副门")) 1 else 0
            w == "才能" ->
                if (next != null && next[0] !in "的和与及或是也都很" && (prev == null || prev.last() !in "的有无其之种项")) 1 else 0
            w == "的话" -> if (tokens.getOrNull(i + 1)?.text?.firstOrNull()?.let { it in "，,：:" } == true) 0 else 1
            w == "就要" -> if (laterInClause(tokens, i, "了")) 0 else 1
            // 却说 «теперь обратимся к» opens a passage; after a subject it is 却 + 说 (哈利却说他像…).
            w == "却说" -> if (prev != null) 1 else 0
            // 可不 «ещё бы» stands alone; before a word it is 可 + 不 (这可不容易 «вот уж нелегко»).
            w == "可不" -> if (next != null) 1 else 0
            w in AFTER_COUNT -> if (prev != null && (prev.last() in COUNT_CHARS || prev.last() in "这那哪")) 1 else 0
            w.length == 2 && w[0] in COUNT_CHARS && w[1] in MEASURE_CHARS && w !in NUMERAL_WORDS -> 1
            else -> 0
        }
    }

    /** [word] is among the tokens after [i], before the clause ends. */
    private fun laterInClause(tokens: List<ZhToken>, i: Int, word: String): Boolean {
        var k = i + 1
        while (k < tokens.size && k <= i + 8) {
            val t = tokens[k]
            if (t.kind == TokenKind.PUNCT && t.text[0] in "，。！？；,.!?;、：:") return false
            if (t.text == word) return true
            k++
        }
        return false
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

    private fun segmentHan(text: String, from: Int, to: Int, out: MutableList<ZhToken>, extra: Set<String>, blocked: Set<String> = emptySet()) {
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
                if (dict.contains(w) && !dict.isLoose(w) && w !in blocked) relax(best, back, i, len, cost(w))
            }
            if (extra.isNotEmpty()) {
                for (len in 2..minOf(MAX_WORD, n - i)) {
                    val w = text.substring(base, base + len)
                    if (w in extra && w !in blocked) relax(best, back, i, len, extraCost)
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
        /** Dictionary words that are 把 + noun before a verb. */
        private val BA_NOUNS = setOf("把门", "把手", "把头")
        /** Measure word + noun after a numeral or demonstrative: 这 本 书, 一 个 人 (not 本书 «эта книга», 个人 «личный»). */
        private val AFTER_COUNT = setOf("本书", "个人")
        private const val COUNT_CHARS = "一二三四五六七八九十两几每半"
        private const val MEASURE_CHARS = "个名种句次家口对把张只条位件本颗杯段块片首篇部座辆台场双份群批项层间支根匹封棵朵顿遍趟番碗瓶盒包串排套架枚粒滴届章页束堆扇幅阵笔届所"
        /** Numeral + measure-looking pairs that are words of their own. */
        private val NUMERAL_WORDS = setOf("一把手", "一口", "一家", "一阵", "一番", "一场", "一对", "一层", "一笔", "半场", "十分", "百分", "千万", "万分", "两口", "一所", "一部", "一片", "一段", "一块", "一句", "一份", "一批", "一项", "一套", "一群")
        private const val NUMERALS = "零〇一二三四五六七八九十百千万亿两"
        private const val UNITS = "年月日号点分秒岁"
    }
}
