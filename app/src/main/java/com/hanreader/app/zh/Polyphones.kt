package com.hanreader.app.zh

/**
 * Readings of polyphones that stand alone as a token, chosen from their neighbours. The
 * dictionary gives one reading per character; in running text the particles 地 and 得 alone
 * occur thousands of times per novel and read "de", not dì / dé.
 *
 * Only standalone single-character tokens are decided here: inside words the dictionary's
 * reading is right (地方 dìfang, 得到 dédào, 长大 zhǎngdà).
 */
object Polyphones {
    /** The reading of token [i] if its context decides it; null keeps the dictionary's. */
    fun reading(tokens: List<ZhToken>, i: Int): String? {
        val t = tokens[i]
        if (t.kind != TokenKind.HAN || t.text.length != 1) return null
        val prev = tokens.getOrNull(i - 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        val next = tokens.getOrNull(i + 1)?.takeIf { it.kind == TokenKind.HAN }?.text
        return when (t.text[0]) {
            '地' -> di(prev, next)
            '得' -> de(prev, next)
            '长' -> chang(prev, next)
            '只' -> zhi(prev, next)
            '还' -> if (next != null && next[0] in "给钱债清" ) "huán" else null
            else -> null
        }
    }

    /** 地: the adverbial particle after a description (慢慢地说, 不耐烦地问), else the earth (在地上). */
    private fun di(prev: String?, next: String?): String {
        if (prev == null || next == null) return "dì"
        if (next[0] in "上下里面板毯底方区点图球址位带铁窖狱震洞道形步主") return "dì"
        if (prev.length >= 2) return "de"
        return if (prev[0] in ADVERB_CHARS) "de" else "dì"
    }

    /** 得: the complement particle (长得, 说得很快) or děi "must" after a subject (我得走了). */
    private fun de(prev: String?, next: String?): String {
        if (prev != null && next != null && prev in SUBJECTS && next !in DEGREE && next[0] !in "很更多太像好对大快慢真不了比") return "děi"
        if (prev == null && next != null && next !in DEGREE) return "děi"
        return "de"
    }

    /** 长: long (很长, 太长) or grow / chief (长得, 长出, 级长). */
    private fun chang(prev: String?, next: String?): String {
        if (next != null && next[0] in "得出大高满着了成") return "zhǎng"
        if (prev != null && prev.last() in "级部院校队班首家族局市省州船村团社组厂处科县乡镇会") return "zhǎng"
        return "cháng"
    }

    /** 只: the measure word after a demonstrative or number (那只猫), else "only" (这只是). */
    private fun zhi(prev: String?, next: String?): String? {
        if (prev == null || prev.last() !in "这那哪每几一两半二三四五六七八九十百") return null
        if (next == null || next in ONLY_BEFORE || next[0] in "是有能会要想好在不听见剩用") return null
        return "zhī"
    }

    private const val ADVERB_CHARS = "狠猛轻静快慢悄默渐微深重很暗偷乖死紧怒冷急"
    private val SUBJECTS = setOf("我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "咱们", "大家", "就", "还", "总", "也", "都", "必", "只", "可", "非", "又", "现在", "那", "这")
    private val DEGREE = setOf("很", "更", "多", "太", "像", "要命", "了", "比", "好", "对", "非常", "特别", "那么", "这么", "不", "清楚", "到", "厉害", "多么", "可怕")
    private val ONLY_BEFORE = setOf("剩下", "知道", "希望", "觉得", "不过", "因为", "是", "有", "能")
}
