package com.hanreader.app.lang

/** Shared punctuation rules for speech, card examples and Spanish context windows. */
object SentenceBoundary {
    private const val CLOSING = "”’」』\")）»"
    /** Abbreviations whose full stop does not end a sentence (Sr. Ortega, EE. UU., pág. 3). */
    private val ABBREVIATIONS = setOf(
        "sr", "sra", "srta", "sres", "dr", "dra", "ud", "uds", "vd", "vds", "d", "dña", "ee", "uu", "etc", "pág", "págs",
        "núm", "nº", "vol", "cap", "art", "av", "avda", "ed", "eds", "fig", "ibíd", "cf", "aprox", "tel", "st", "mr", "mrs", "ms"
    )

    /**
     * Whether the mark at [i] ends a sentence. A full stop does not after an abbreviation or an
     * initial (J. R. R.), between digits (1.500), or when the text goes on in lower case.
     */
    fun ends(text: String, i: Int): Boolean {
        if (text[i] != '.') return true
        if (i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()) return false
        var w = i
        while (w > 0 && text[w - 1].isLetter()) w--
        val word = text.substring(w, i)
        if (word.lowercase() in ABBREVIATIONS) return false
        if (word.length == 1 && word[0].isUpperCase()) return false
        var n = i + 1
        while (n < text.length && (text[n].isWhitespace() || text[n] in CLOSING)) n++
        if (n < text.length && text[n].isLowerCase()) return false
        return true
    }

}
