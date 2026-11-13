package com.hanreader.app.zh

/**
 * Readings of polyphones that stand alone as a token, chosen from their neighbours. The
 * dictionary gives one reading per character; in running text the particles 地 and 得 alone
 * occur thousands of times per novel and read "de", not dì / dé.
 *
 * The rules are data (assets/zh/context.tsv, see [ZhRules]); each looks only at the neighbouring
 * tokens, and when they do not decide, the dictionary's reading stays (null). Only standalone single-character tokens are decided here: inside words the dictionary's
 * reading is right (地方 dìfang, 得到 dédào, 长大 zhǎngdà).
 */
object Polyphones {
    /** The reading of token [i] if its context decides it; null keeps the dictionary's. */
    fun reading(tokens: List<ZhToken>, i: Int): String? {
        val t = tokens[i]
        if (t.kind != TokenKind.HAN) return null
        val prev = tokens.getOrNull(i - 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        val next = tokens.getOrNull(i + 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        return ZhRules.current.reading(t.text, prev, next, tokens, i)?.ifEmpty { null }
    }

    /**
     * Readings of the characters of token [i], a combination the dictionary gives no reading
     * (两行, 数一数, 重写): each character decided by the rest of the word and the neighbours, null
     * where nothing decides. Null for single characters and dictionary words.
     */
    fun readingsInside(tokens: List<ZhToken>, i: Int): List<String?>? {
        val t = tokens[i]
        if (t.kind != TokenKind.HAN || t.text.length < 2 || t.entry?.pinyin?.isNotEmpty() == true) return null
        val before = tokens.getOrNull(i - 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        val after = tokens.getOrNull(i + 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        val w = t.text
        val out = w.indices.map { k ->
            when {
                w[k].isSurrogate() -> null
                // Inside a combination 得 is mostly dé (易得, 所得者) and 地 the earth (青草地), except
                // after a doubled description: 默默地, 悄悄地.
                w[k] == '得' -> null
                w[k] == '地' -> if (k == w.lastIndex && k >= 2 && w[k - 1] == w[k - 2]) "de" else null
                else -> ZhRules.current.reading(w[k].toString(), if (k == 0) before else w.substring(0, k), if (k == w.lastIndex) after else w.substring(k + 1))?.ifEmpty { null }
            }
        }
        return out.takeIf { r -> r.any { it != null } }
    }
}
