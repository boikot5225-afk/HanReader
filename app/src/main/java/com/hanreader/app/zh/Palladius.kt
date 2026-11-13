package com.hanreader.app.zh

import java.text.Normalizer

/**
 * Pinyin to Russian by the Palladius system, the usual spelling of Chinese names in Russian books:
 * luó fēi → «Ло Фэй», zhāng zhì gāng → «Чжан Чжиган», xiè → «Се».
 */
object Palladius {
    private val INITIALS = listOf("zh" to "чж", "ch" to "ч", "sh" to "ш", "b" to "б", "p" to "п", "m" to "м", "f" to "ф",
        "d" to "д", "t" to "т", "n" to "н", "l" to "л", "g" to "г", "k" to "к", "h" to "х", "j" to "цз", "q" to "ц",
        "x" to "с", "r" to "ж", "z" to "цз", "c" to "ц", "s" to "с")

    private val FINALS = mapOf(
        "a" to "а", "o" to "о", "e" to "э", "ai" to "ай", "ei" to "эй", "ao" to "ао", "ou" to "оу", "an" to "ань",
        "en" to "энь", "ang" to "ан", "eng" to "эн", "ong" to "ун", "er" to "эр", "i" to "и", "ia" to "я", "ie" to "е",
        "iao" to "яо", "iu" to "ю", "ian" to "янь", "in" to "инь", "iang" to "ян", "ing" to "ин", "iong" to "юн",
        "u" to "у", "ua" to "уа", "uo" to "о", "uai" to "уай", "ui" to "уй", "uan" to "уань", "un" to "унь",
        "uang" to "уан", "v" to "юй", "ve" to "юэ", "ue" to "юэ", "van" to "юань", "vn" to "юнь"
    )

    /** Syllables without an initial (and y-, w- spellings). */
    private val WHOLE = mapOf(
        "a" to "а", "o" to "о", "e" to "э", "ai" to "ай", "ei" to "эй", "ao" to "ао", "ou" to "оу", "an" to "ань",
        "en" to "энь", "ang" to "ан", "eng" to "эн", "er" to "эр",
        "ya" to "я", "yo" to "ё", "ye" to "е", "yao" to "яо", "you" to "ю", "yan" to "янь", "yin" to "инь",
        "yang" to "ян", "ying" to "ин", "yong" to "юн", "yi" to "и", "yu" to "юй", "yue" to "юэ", "yuan" to "юань",
        "yun" to "юнь", "wa" to "ва", "wo" to "во", "wai" to "вай", "wei" to "вэй", "wan" to "вань", "wen" to "вэнь",
        "wang" to "ван", "weng" to "вэн", "wu" to "у"
    )

    /** One syllable (with or without tone marks, ü as ü or v); null if it is not pinyin. */
    fun syllable(pinyin: String): String? {
        val s = Normalizer.normalize(pinyin.lowercase().replace("u:", "v"), Normalizer.Form.NFD)
            .replace("u\u0308", "v").filter { it in 'a'..'z' }
        if (s.isEmpty()) return null
        WHOLE[s]?.let { return it }
        val (latin, cyr) = INITIALS.firstOrNull { s.startsWith(it.first) } ?: return null
        var fin = s.substring(latin.length)
        // ju, qu, xu are jü, qü, xü.
        if (latin in setOf("j", "q", "x") && fin.startsWith("u")) fin = "v" + fin.substring(1)
        // zi, ci, si: «цзы, цы, сы»; zhi, chi, shi, ri: «чжи, чи, ши, жи».
        if (fin == "i" && latin in setOf("z", "c", "s")) return cyr + "ы"
        return FINALS[fin]?.let { cyr + it }
    }

    /**
     * A name from its syllables: a Chinese name as surname and given name («Ло Фэй», «Юань Чжибан»),
     * a foreign one in one word («Дэсыли»). Null if a syllable is not pinyin.
     */
    fun name(syllables: List<String>, chinese: Boolean): String? {
        val parts = syllables.filter { it.isNotBlank() }.map { syllable(it) ?: return null }
        if (parts.isEmpty()) return null
        fun cap(x: String) = x.replaceFirstChar { it.uppercase() }
        return if (chinese && parts.size >= 2) cap(parts.first()) + " " + cap(parts.drop(1).joinToString(""))
        else cap(parts.joinToString(""))
    }
}
