package com.hanreader.app.zh

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

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
 * The offline Chinese lexicon (~190k words: CC-CEDICT plus Jieba-only words), read straight
 * from the binary file built by tools/build_lexicon_bin.py: a ready-made hash table, word
 * characters, ranks, frequencies, pinyin and English meanings. Nothing is parsed at start: the
 * file is mapped into memory and every lookup reads it in place (thread-safe absolute reads).
 */
class ZhDictionary private constructor(private val b: ByteBuffer) {
    val size: Int
    val maxWordLength: Int
    /** Sum of all Jieba counts, the denominator for word probabilities. */
    val totalFreq: Long
    private val tableSize: Int
    private val wordOff: Int
    private val wordChars: Int
    private val table: Int
    private val ranks: Int
    private val freqs: Int
    private val pyOff: Int
    private val py: Int
    private val glOff: Int
    private val gl: Int

    init {
        b.order(ByteOrder.LITTLE_ENDIAN)
        if (b.get(0) != 'H'.code.toByte() || b.get(1) != 'R'.code.toByte() || b.get(2) != 'L'.code.toByte() || b.get(3) != 'X'.code.toByte()) {
            throw IllegalArgumentException("Not a HanReader lexicon")
        }
        require(b.getInt(4) == VERSION) { "Lexicon version ${b.getInt(4)} not supported" }
        size = b.getInt(8)
        tableSize = b.getInt(12)
        maxWordLength = b.getInt(16)
        totalFreq = (b.getInt(20).toLong() and 0xFFFFFFFFL) or (b.getInt(24).toLong() shl 32)
        wordOff = 28
        wordChars = wordOff + (size + 1) * 4
        table = wordChars + pad4(b.getInt(wordOff + size * 4) * 2)
        ranks = table + tableSize * 4
        freqs = ranks + size * 4
        pyOff = freqs + size * 4
        py = pyOff + (size + 1) * 4
        glOff = py + pad4(b.getInt(pyOff + size * 4))
        gl = glOff + (size + 1) * 4
    }

    private fun index(word: String): Int {
        if (word.isEmpty() || word.length > maxWordLength) return -1
        val mask = tableSize - 1
        var slot = word.hashCode() and mask
        while (true) {
            val e = b.getInt(table + slot * 4)
            if (e == 0) return -1
            val i = e - 1
            if (matches(i, word)) return i
            slot = (slot + 1) and mask
        }
    }

    private fun matches(i: Int, word: String): Boolean {
        val start = b.getInt(wordOff + i * 4)
        val end = b.getInt(wordOff + (i + 1) * 4)
        if (end - start != word.length) return false
        for (k in word.indices) if (b.getChar(wordChars + (start + k) * 2) != word[k]) return false
        return true
    }

    private fun utf8(offsets: Int, data: Int, i: Int): String {
        val s = b.getInt(offsets + i * 4)
        val e = b.getInt(offsets + (i + 1) * 4)
        if (e <= s) return ""
        val bytes = ByteArray(e - s)
        b.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(data + s) }.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun contains(word: String): Boolean = index(word) >= 0

    /** Jieba frequency: 0 when the word is known but has no count, -1 when absent. */
    fun freq(word: String): Int = index(word).let { if (it < 0) -1 else b.getInt(freqs + it * 4) }

    /** HSK frequency rank: 0 when unranked, -1 when absent. */
    fun rank(word: String): Int = index(word).let { if (it < 0) -1 else b.getInt(ranks + it * 4) and FLAGS.inv() }

    /**
     * A Jieba combination without a meaning that is not a name (一个, 放在, 是因为): known, with
     * its count, but its parts are the words. The segmenter does not keep it whole.
     */
    fun isLoose(word: String): Boolean = index(word).let { it >= 0 && b.getInt(ranks + it * 4) and LOOSE != 0 }

    fun lookup(word: String): ZhEntry? {
        val i = index(word)
        if (i < 0) return null
        return ZhEntry(word, utf8(pyOff, py, i), utf8(glOff, gl, i), b.getInt(ranks + i * 4) and FLAGS.inv(), b.getInt(freqs + i * 4))
    }

    /** Every word in sorted order (tests, tools). */
    fun word(i: Int): String {
        val s = b.getInt(wordOff + i * 4)
        val e = b.getInt(wordOff + (i + 1) * 4)
        return String(CharArray(e - s) { b.getChar(wordChars + (s + it) * 2) })
    }

    companion object {
        private const val VERSION = 1
        private const val HAS_DEF = 1 shl 30
        private const val LOOSE = 1 shl 29
        private const val FLAGS = HAS_DEF or LOOSE

        private fun pad4(n: Int) = (n + 3) and 3.inv()

        /** Maps a lexicon file into memory (read-only, shared, no parsing). */
        fun map(file: File): ZhDictionary = RandomAccessFile(file, "r").use { f ->
            ZhDictionary(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()))
        }

        fun fromBytes(bytes: ByteArray): ZhDictionary = ZhDictionary(ByteBuffer.wrap(bytes))
    }
}
