package com.hanreader.app.zh

/**
 * Chinese numbers written in characters, read as a short Russian note: 一九三一年 «1931 год»,
 * 十四日 «14-е число», 第九章 «глава 9», 四两 «4 ляна», 两三 «2–3», 二十千 «20 тысяч».
 * The dictionary has only some of them, and splitting the rest gives nonsense (一九 + 三一).
 */
object ZhNumbers {
    private val DIGITS = mapOf('〇' to 0, '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    private val POWERS = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10_000)

    /** The note for [word], or null when it is not a number (with an optional 第 and one unit). */
    fun gloss(word: String): String? {
        var w = word
        val ordinal = w.startsWith("第")
        if (ordinal) w = w.substring(1)
        val unit = w.lastOrNull()?.takeIf { it in UNITS && w.length > 1 && !(it == '两' && w.length == 1) }
        if (unit != null) w = w.dropLast(1)
        if (w.isEmpty() || w.any { it !in DIGITS && it !in POWERS }) return null
        if (!ordinal && unit == null && w.length < 2) return null
        val range = rangeOf(w)
        val n: Long? = if (range != null) null else valueOf(w) ?: return null
        val shown = range?.let { "${it.first}–${it.second}" } ?: n.toString()
        val count = range?.second?.toLong() ?: n!!
        return when (unit) {
            null -> if (ordinal) "$shown-й" else shown
            '年' -> if (range == null && w.none { it in POWERS } && w.length >= 3) "$shown год" else "$shown ${plural(count, "год", "года", "лет")}"
            '月' -> if (ordinal || range == null) "$shown-й месяц" else "$shown ${plural(count, "месяц", "месяца", "месяцев")}"
            '日', '号' -> "$shown-е число"
            '章' -> "глава $shown"
            '回' -> if (ordinal) "глава $shown" else "$shown ${plural(count, "раз", "раза", "раз")}"
            '两' -> "$shown ${plural(count, "лян", "ляна", "лянов")}"
            '寸' -> "$shown ${plural(count, "цунь", "цуня", "цуней")}"
            '尺' -> "$shown ${plural(count, "чи", "чи", "чи")}"
            '斤' -> "$shown ${plural(count, "цзинь", "цзиня", "цзиней")}"
            '里' -> "$shown ${plural(count, "ли", "ли", "ли")}"
            '岁' -> "$shown ${plural(count, "год", "года", "лет")} (о возрасте)"
            '天' -> "$shown ${plural(count, "день", "дня", "дней")}"
            '次' -> "$shown ${plural(count, "раз", "раза", "раз")}"
            '个' -> "$shown (штук)"
            '分' -> "$shown фэнь (доля, минута)"
            '点' -> "$shown ${plural(count, "час", "часа", "часов")}"
            else -> null
        }
    }

    private const val UNITS = "年月日号章回两寸尺斤里岁天次个分点"

    /** 五六, 两三: «5–6»; null if not two neighbouring digits. */
    private fun rangeOf(w: String): Pair<Int, Int>? {
        if (w.length != 2) return null
        val a = DIGITS[w[0]] ?: return null
        val b = DIGITS[w[1]] ?: return null
        return if (b == a + 1 && a > 0) a to b else null
    }

    /** 一九三一 -> 1931 (digit by digit); 一百五十 -> 150; 二十千 -> 20000. */
    private fun valueOf(w: String): Long? {
        if (w.none { it in POWERS }) return w.fold(0L) { acc, c -> acc * 10 + (DIGITS[c] ?: return null) }
        var total = 0L
        var section = 0L
        var digit = -1L
        for (c in w) {
            val d = DIGITS[c]
            if (d != null) { digit = d.toLong(); continue }
            val p = POWERS.getValue(c).toLong()
            if (p == 10_000L || (p == 1000L && digit < 0 && section > 0)) {
                // 万, and 二十千 (twenty thousands): everything so far times the power.
                total = (total + section + maxOf(digit, 0)) * p
                section = 0; digit = -1
            } else {
                section += (if (digit < 0) 1 else digit) * p
                digit = -1
            }
        }
        return total + section + maxOf(digit, 0)
    }

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> many
            m10 == 1L -> one
            m10 in 2..4 -> few
            else -> many
        }
    }
}
