package com.hanreader.app.es

import java.io.InputStream

/**
 * A sorted TSV file held as bytes: lines sorted by their UTF-8 bytes, the key is the text before
 * the first tab. Lookups are binary searches in place, without a String per line (the forms table
 * has about half a million lines).
 */
class SortedTsv private constructor(private val b: ByteArray, private val starts: IntArray) {
    val size: Int get() = starts.size

    /** The fields after the key of the line with [key], or null. */
    fun get(key: String): List<String>? {
        val k = key.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(starts[mid], k)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return fields(mid)
            }
        }
        return null
    }

    fun contains(key: String): Boolean = get(key) != null

    /** Every line as key and fields after it, in order. */
    fun forEach(action: (String, List<String>) -> Unit) {
        for (k in starts.indices) {
            val s = starts[k]
            var e = s
            while (e < b.size && b[e] != NL) e++
            val parts = String(b, s, e - s, Charsets.UTF_8).split('\t')
            action(parts[0], parts.drop(1))
        }
    }

    /** Compares the key of the line starting at [at] with [k], as unsigned bytes. */
    private fun compare(at: Int, k: ByteArray): Int {
        var i = 0
        while (true) {
            val p = at + i
            val endOfKey = p >= b.size || b[p] == TAB || b[p] == NL
            if (endOfKey) return if (i == k.size) 0 else -1
            if (i == k.size) return 1
            val x = b[p].toInt() and 0xFF
            val y = k[i].toInt() and 0xFF
            if (x != y) return x - y
            i++
        }
    }

    private fun fields(line: Int): List<String> {
        val s = starts[line]
        var e = s
        while (e < b.size && b[e] != NL) e++
        return String(b, s, e - s, Charsets.UTF_8).split('\t').drop(1)
    }

    companion object {
        private const val TAB = '\t'.code.toByte()
        private const val NL = '\n'.code.toByte()

        fun read(input: InputStream): SortedTsv = of(input.use { it.readBytes() })

        fun of(bytes: ByteArray): SortedTsv {
            var n = 0
            for (i in bytes.indices) if (bytes[i] == NL) n++
            if (bytes.isNotEmpty() && bytes.last() != NL) n++
            val starts = IntArray(n)
            var k = 0
            var at = 0
            for (i in bytes.indices) {
                if (bytes[i] == NL) {
                    if (i > at) starts[k++] = at
                    at = i + 1
                }
            }
            if (at < bytes.size) starts[k++] = at
            return SortedTsv(bytes, if (k == n) starts else starts.copyOf(k))
        }
    }
}
