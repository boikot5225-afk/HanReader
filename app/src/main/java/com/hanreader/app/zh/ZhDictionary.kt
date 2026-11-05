package com.hanreader.app.zh

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * One lexicon entry. [pinyin]/[gloss] come from CC-CEDICT and are empty for words known only
 * to the Jieba lexicon. [rank] is the HSK frequency rank (0 = unranked); [freq] the Jieba count.
 */
data class ZhEntry(val word: String, val pinyin: String, val gloss: String, val rank: Int, val freq: Int) {
    val hasDefinition: Boolean get() = gloss.isNotEmpty()

    /** The first few senses, short enough for a flash card. */
    fun shortGloss(maxSenses: Int = 3): String =
        gloss.split("; ", "/").map { it.trim() }.filter { it.isNotEmpty() }.take(maxSenses).joinToString("; ")
}

/**
 * Offline Chinese dictionary kept compact in memory: words in one hash map pointing to
 * parallel arrays, and the English text in a single UTF-8 byte buffer decoded on lookup.
 * About 190k words: 121k CC-CEDICT entries plus 69k Jieba-only words for segmentation.
 */
class ZhDictionary private constructor(
    private val index: HashMap<String, Int>,
    private val pinyin: Array<String>,
    private val ranks: IntArray,
    private val freqs: IntArray,
    private val glossBytes: ByteArray,
    private val glossOffsets: IntArray,
    val maxWordLength: Int
) {
    val size: Int get() = pinyin.size

    fun contains(word: String): Boolean = index.containsKey(word)

    /** Jieba frequency: 0 when the word is known but has no count, -1 when absent. */
    fun freq(word: String): Int = index[word]?.let { freqs[it] } ?: -1

    /** Sum of all Jieba counts, the denominator for word probabilities. */
    val totalFreq: Long by lazy { freqs.fold(0L) { acc, f -> acc + f } }

    fun lookup(word: String): ZhEntry? {
        val i = index[word] ?: return null
        val start = glossOffsets[i]
        val end = glossOffsets[i + 1]
        return ZhEntry(word, pinyin[i], String(glossBytes, start, end - start, Charsets.UTF_8), ranks[i], freqs[i])
    }

    companion object {
        /** Reads assets/zh/lexicon.dat: word \t pinyin \t english \t hskRank \t jiebaFreq. */
        fun load(gz: InputStream): ZhDictionary =
            parse(BufferedReader(InputStreamReader(GZIPInputStream(gz, 1 shl 16), Charsets.UTF_8), 1 shl 16))

        fun parse(reader: BufferedReader): ZhDictionary {
            val index = HashMap<String, Int>(1 shl 19)
            val pinyin = ArrayList<String>(200_000)
            val ranks = ArrayList<Int>(200_000)
            val freqs = ArrayList<Int>(200_000)
            val gloss = java.io.ByteArrayOutputStream(8 shl 20)
            val offsets = ArrayList<Int>(200_001)
            var maxLen = 1
            reader.useLines { lines ->
                for (line in lines) {
                    val a = line.indexOf('\t')
                    val b = line.indexOf('\t', a + 1)
                    val c = line.indexOf('\t', b + 1)
                    val d = line.indexOf('\t', c + 1)
                    if (a <= 0 || b < 0 || c < 0 || d < 0) continue
                    val word = line.substring(0, a)
                    if (index.containsKey(word)) continue
                    index[word] = pinyin.size
                    pinyin.add(line.substring(a + 1, b))
                    offsets.add(gloss.size())
                    val bytes = line.substring(b + 1, c).toByteArray(Charsets.UTF_8)
                    gloss.write(bytes, 0, bytes.size)
                    ranks.add(line.substring(c + 1, d).toIntOrNull() ?: 0)
                    freqs.add(line.substring(d + 1).trim().toIntOrNull() ?: 0)
                    if (word.length > maxLen) maxLen = word.length
                }
            }
            offsets.add(gloss.size())
            return ZhDictionary(
                index,
                pinyin.toTypedArray(),
                ranks.toIntArray(),
                freqs.toIntArray(),
                gloss.toByteArray(),
                offsets.toIntArray(),
                maxLen
            )
        }
    }
}
