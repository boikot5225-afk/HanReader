package com.hanreader.app.es

import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel

/**
 * A sorted TSV file held as bytes: lines sorted by their UTF-8 bytes, the key is the text before
 * the first tab. Lookups are binary searches in place, without a String per line (the forms table
 * has about half a million lines). On the phone the file and its line index are mapped from app
 * storage ([map]): nothing is read or parsed at start.
 */
class SortedTsv private constructor(private val b: ByteBuffer, private val starts: IntBuffer) {
    val size: Int get() = starts.limit()
    private val length = b.limit()

    /** The fields after the key of the line with [key], or null. */
    fun get(key: String): List<String>? {
        val k = key.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(starts.get(mid), k)
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
        for (k in 0 until size) {
            val parts = line(k).split('\t')
            action(parts[0], parts.drop(1))
        }
    }

    /**
     * Lines whose fields hold [needle] right after a tab, «|» or «!» (an item of a list), decoded
     * only when they match: a scan of the forms table for one verb's forms stays cheap.
     */
    fun forEachWithItem(needle: String, action: (String, List<String>) -> Unit) {
        val k = needle.toByteArray(Charsets.UTF_8)
        for (line in 0 until size) {
            val s = starts.get(line)
            var e = s
            while (e < length && b.get(e) != NL) e++
            var p = s
            var hit = false
            while (!hit && p + k.size <= e) {
                val before = if (p > s) b.get(p - 1) else 0
                if (before == TAB || before == PIPE || before == BANG) {
                    var j = 0
                    while (j < k.size && b.get(p + j) == k[j]) j++
                    if (j == k.size) hit = true
                }
                p++
            }
            if (hit) {
                val parts = line(line).split('\t')
                action(parts[0], parts.drop(1))
            }
        }
    }

    /** Compares the key of the line starting at [at] with [k], as unsigned bytes. */
    private fun compare(at: Int, k: ByteArray): Int {
        var i = 0
        while (true) {
            val p = at + i
            val endOfKey = p >= length || b.get(p) == TAB || b.get(p) == NL
            if (endOfKey) return if (i == k.size) 0 else -1
            if (i == k.size) return 1
            val x = b.get(p).toInt() and 0xFF
            val y = k[i].toInt() and 0xFF
            if (x != y) return x - y
            i++
        }
    }

    private fun line(k: Int): String {
        val s = starts.get(k)
        var e = s
        while (e < length && b.get(e) != NL) e++
        val bytes = ByteArray(e - s)
        for (j in bytes.indices) bytes[j] = b.get(s + j)
        return String(bytes, Charsets.UTF_8)
    }

    private fun fields(k: Int): List<String> = line(k).split('\t').drop(1)

    companion object {
        private const val TAB = '\t'.code.toByte()
        private const val NL = '\n'.code.toByte()
        private const val PIPE = '|'.code.toByte()
        private const val BANG = '!'.code.toByte()

        fun read(input: InputStream): SortedTsv = of(input.use { it.readBytes() })

        fun of(bytes: ByteArray): SortedTsv {
            val buf = ByteBuffer.wrap(bytes)
            return SortedTsv(buf, IntBuffer.wrap(lineStarts(buf)))
        }

        /** Maps [data] and its line index [index] (built and written next to it the first time). */
        fun map(data: File, index: File): SortedTsv {
            val buf = RandomAccessFile(data, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            if (!index.isFile || index.length() == 0L) {
                val starts = lineStarts(buf)
                val tmp = File(index.path + ".tmp")
                DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out -> starts.forEach { out.writeInt(it) } }
                tmp.renameTo(index)
            }
            val idx = RandomAccessFile(index, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }.asIntBuffer()
            return SortedTsv(buf, idx)
        }

        private fun lineStarts(b: ByteBuffer): IntArray {
            val n = b.limit()
            var count = 0
            for (i in 0 until n) if (b.get(i) == NL) count++
            if (n > 0 && b.get(n - 1) != NL) count++
            val starts = IntArray(count)
            var k = 0
            var at = 0
            for (i in 0 until n) {
                if (b.get(i) == NL) {
                    if (i > at) starts[k++] = at
                    at = i + 1
                }
            }
            if (at < n) starts[k++] = at
            return if (k == count) starts else starts.copyOf(k)
        }
    }
}
