package com.hanreader.app.es

/**
 * Where a Spanish word may be broken across lines: between syllables, by the usual rules.
 * - Vowels next to each other are never split across lines (diphthongs ie, ua; hiatus le-er, rí-o).
 * - One consonant between vowels starts the next syllable: ca-sa. ch, ll and rr count as one: co-che.
 * - Two consonants are split (can-to) unless they form a pair that starts a syllable: pl, bl, fl, cl,
 *   gl, pr, br, fr, cr, gr, tr, dr, kr, kl (o-tro). Three: the last two stay together if they are
 *   such a pair (ham-bre), else the first two go left (ins-tante). Four: two and two (obs-truir).
 * At least two letters stay on each side, so no single letter is left at a line edge.
 */
object EsHyphenator {
    private const val VOWELS = "aeiouáéíóúü"
    private val ONSET_PAIRS = setOf("pl", "bl", "fl", "cl", "gl", "pr", "br", "fr", "cr", "gr", "tr", "dr", "kr", "kl")

    private fun isVowel(c: Char) = c in VOWELS

    /** Offsets in [word] where a hyphen may go (a break before that character). */
    fun points(word: String): List<Int> {
        if (word.length < 4 || word.any { !it.isLetter() }) return emptyList()
        val w = word.lowercase()
        // Units: a letter, or ch / ll / rr as one consonant.
        val start = ArrayList<Int>()
        var i = 0
        while (i < w.length) {
            start += i
            i += if (i + 1 < w.length && w.substring(i, i + 2) in setOf("ch", "ll", "rr")) 2 else 1
        }
        fun unit(k: Int) = w.substring(start[k], if (k + 1 < start.size) start[k + 1] else w.length)
        fun vowelUnit(k: Int) = unit(k).length == 1 && isVowel(unit(k)[0])
        // "qu" and "gu" before e/i: the u is silent, part of the consonant.
        fun silentU(k: Int) = unit(k) == "u" && k > 0 && unit(k - 1) in setOf("q", "g") && k + 1 < start.size && unit(k + 1) in setOf("e", "i", "é", "í")

        val breaks = ArrayList<Int>()
        var k = 0
        val n = start.size
        // Skip the consonants before the first vowel.
        while (k < n && (!vowelUnit(k) || silentU(k))) k++
        while (k < n) {
            // The vowels together. A hiatus (le-er, rí-o) is two syllables, but typesetting never
            // splits neighbouring vowels across lines, so it gives no break point.
            var end = k + 1
            while (end < n && vowelUnit(end) && !silentU(end)) end++
            // The consonants up to the next vowel.
            var next = end
            while (next < n && (!vowelUnit(next) || silentU(next))) next++
            if (next >= n) break
            val cons = (end until next).map { unit(it) }.filter { it != "u" }
            val firstCons = end
            when (cons.size) {
                0 -> breaks += start[next]
                1 -> breaks += start[next - 1].let { if (silentU(next - 1)) start[next - 2] else it }
                2 -> breaks += if (cons[0] + cons[1] in ONSET_PAIRS) start[firstCons] else start[firstCons + 1]
                3 -> breaks += if (cons[1] + cons[2] in ONSET_PAIRS) start[firstCons + 1] else start[firstCons + 2]
                else -> breaks += start[firstCons + 2]
            }
            k = next
        }
        return breaks.filter { it >= 2 && word.length - it >= 2 }.distinct().sorted()
    }

    /** The word with hyphens at every point, for tests: "me-dia-no-che". */
    fun syllables(word: String): String {
        val p = points(word)
        val sb = StringBuilder(word)
        for (x in p.reversed()) sb.insert(x, '-')
        return sb.toString()
    }
}
