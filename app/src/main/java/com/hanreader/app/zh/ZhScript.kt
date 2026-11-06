package com.hanreader.app.zh

import java.io.InputStream

/**
 * Simplified <-> traditional characters (OpenCC tables, assets/zh/script.tsv). Every conversion
 * keeps the text length, so offsets of tokens, positions and highlights stay valid.
 *
 * The dictionary and the segmenter work on simplified text: a traditional book is converted for
 * analysis and shown in the script the reader chose.
 */
class ZhScript(
    private val toSimp: Map<Char, Char>,
    private val toTrad: Map<Char, Char>,
    private val phrases: Map<String, String>
) {
    private val phraseStarts: Set<Char> = phrases.keys.mapTo(HashSet()) { it[0] }
    private val maxPhrase = (phrases.keys.maxOfOrNull { it.length } ?: 0).coerceAtMost(MAX_PHRASE)

    fun toSimplified(text: String): String {
        if (toSimp.isEmpty()) return text
        var out: CharArray? = null
        for (i in text.indices) {
            val c = toSimp[text[i]] ?: continue
            if (out == null) out = text.toCharArray()
            out[i] = c
        }
        return out?.let { String(it) } ?: text
    }

    /** Simplified [text] in traditional characters: phrases first (头发 -> 頭髮), then characters. */
    fun toTraditional(text: String): String {
        if (toTrad.isEmpty()) return text
        val out = text.toCharArray()
        var i = 0
        while (i < text.length) {
            var used = 0
            if (text[i] in phraseStarts) {
                for (len in minOf(maxPhrase, text.length - i) downTo 2) {
                    val p = phrases[text.substring(i, i + len)] ?: continue
                    p.toCharArray(out, i)
                    used = len
                    break
                }
            }
            if (used == 0) {
                toTrad[text[i]]?.let { out[i] = it }
                used = 1
            }
            i += used
        }
        return String(out)
    }

    /** A chapter's paragraphs for the dictionary: simplified if the chapter is in traditional script. */
    fun forDictionary(texts: List<String>): List<String> =
        if (looksTraditional(texts)) texts.map { toSimplified(it) } else texts

    fun looksTraditional(texts: List<String>): Boolean {
        val sample = StringBuilder()
        for (t in texts) { sample.append(t); if (sample.length > 20_000) break }
        return looksTraditional(sample.toString())
    }

    /** True if [text] has characters written only in traditional script (a traditional book). */
    fun looksTraditional(text: String): Boolean {
        var trad = 0
        var han = 0
        for (c in text) {
            if (c in '一'..'鿿') {
                han++
                if (c in toSimp) trad++
            }
        }
        return han > 0 && trad * 50 >= han // 2%+ of the characters
    }

    companion object {
        private const val MAX_PHRASE = 8
        val NONE = ZhScript(emptyMap(), emptyMap(), emptyMap())

        fun parse(input: InputStream): ZhScript {
            val toSimp = HashMap<Char, Char>(4096)
            val toTrad = HashMap<Char, Char>(4096)
            val phrases = HashMap<String, String>(16384)
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.length < 5 || line[0] == '#' || line[1] != '\t') continue
                    val tab = line.indexOf('\t', 2)
                    if (tab < 0) continue
                    val k = line.substring(2, tab)
                    val v = line.substring(tab + 1)
                    if (k.length != v.length) continue
                    when (line[0]) {
                        't' -> toSimp[k[0]] = v[0]
                        's' -> toTrad[k[0]] = v[0]
                        'p' -> phrases[k] = v
                    }
                }
            }
            return ZhScript(toSimp, toTrad, phrases)
        }
    }
}
