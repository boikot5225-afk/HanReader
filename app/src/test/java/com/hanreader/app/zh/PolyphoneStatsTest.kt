package com.hanreader.app.zh

import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.epub.EpubBook
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Developer-only statistics for polyphone readings: which polyphones stand alone as tokens in a
 * real book, how often, and their neighbouring characters (counts only, never the book's text).
 * HANREADER_TEST_EPUB=book.epub HANREADER_COUNTS_OUT=out.tsv (polyphone list in out.tsv.poly)
 */
class PolyphoneStatsTest {
    @Test
    fun standalonePolyphones() {
        val path = System.getenv("HANREADER_TEST_EPUB")
        val out = System.getenv("HANREADER_COUNTS_OUT")
        assumeTrue(path != null && out != null && File(path).exists() && File("$out.poly").exists())
        val poly = File("$out.poly").readText().toSet()
        val seg = ZhSegmenterTest.segmenter
        val count = HashMap<Char, Int>()
        val prev = HashMap<Char, HashMap<String, Int>>()
        val next = HashMap<Char, HashMap<String, Int>>()
        EpubBook(File(path!!)).use { book ->
            for (c in book.chapters) for (b in book.blocks(c.index)) {
                if (b !is EpubBlock.Text) continue
                val t = seg.segment(b.text)
                for ((i, tok) in t.withIndex()) {
                    if (tok.kind != TokenKind.HAN || tok.text.length != 1 || tok.text[0] !in poly) continue
                    val ch = tok.text[0]
                    count[ch] = (count[ch] ?: 0) + 1
                    val p = t.getOrNull(i - 1)?.takeIf { it.kind == TokenKind.HAN }?.text ?: "∅"
                    val n = t.getOrNull(i + 1)?.takeIf { it.kind == TokenKind.HAN }?.text ?: "∅"
                    prev.getOrPut(ch) { HashMap() }.merge(p, 1, Int::plus)
                    next.getOrPut(ch) { HashMap() }.merge(n, 1, Int::plus)
                }
            }
        }
        // How many standalone readings the context rules change, per character (counts only).
        val changed = HashMap<String, Int>()
        EpubBook(File(path)).use { book ->
            for (c in book.chapters) for (b in book.blocks(c.index)) {
                if (b !is EpubBlock.Text) continue
                val t = seg.segment(b.text)
                for (i in t.indices) {
                    val r = Polyphones.reading(t, i) ?: continue
                    val dictReading = t[i].entry?.pinyin
                    if (r != dictReading) changed.merge("${t[i].text}→$r", 1, Int::plus)
                }
            }
        }
        File("$out.changed").writeText(changed.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.key}\t${it.value}" })
        File(out!!).printWriter().use { w ->
            for ((ch, n) in count.entries.sortedByDescending { it.value }.take(120)) {
                fun top(m: Map<String, Int>?) = m.orEmpty().entries.sortedByDescending { it.value }.take(12).joinToString(" ") { "${it.key}:${it.value}" }
                w.println("$ch\t$n\t${ZhSegmenterTest.dict.lookup(ch.toString())?.pinyin}\tprev ${top(prev[ch])}\tnext ${top(next[ch])}")
            }
        }
    }
}
