package com.hanreader.app.reader

import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.epub.BookSource
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.isHan
import java.text.Normalizer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** One search result: where it is and the text around it. */
data class SearchHit(
    val chapter: Int,
    val chapterTitle: String,
    val block: Int,
    /** Character range of the match in the paragraph text. */
    val start: Int,
    val end: Int,
    val snippet: String,
    /** Range of the match inside [snippet]. */
    val inSnippet: IntRange
)

object BookSearch {
    const val MAX_HITS = 300
    private const val CONTEXT = 18

    /** Pinyin without tones, spaces or capitals: "Zhōng guó" -> "zhongguo"; ü becomes v. */
    fun toneless(pinyin: String): String {
        // Decompose first: "ǚ" is one character; after NFD it is u + diaeresis + tone mark.
        val n = Normalizer.normalize(pinyin.lowercase(), Normalizer.Form.NFD).replace("u\u0308", "v")
        return buildString { for (c in n) if (c in 'a'..'z') append(c) }
    }

    /** A query with any Han character is searched as text, otherwise as pinyin. */
    fun isPinyinQuery(q: String): Boolean = q.none { it.isHan() } && q.any { it.isLetter() }

    /**
     * Searches every chapter, calling [onHit] for each match in reading order and [onProgress]
     * after each chapter. Stops at [MAX_HITS]. Cancellable between paragraphs.
     */
    suspend fun search(
        epub: BookSource,
        query: String,
        engine: ZhEngine,
        onProgress: (Int, Int) -> Unit,
        onHit: (SearchHit) -> Unit
    ) {
        val q = query.trim()
        if (q.isEmpty()) return
        val pinyin = isPinyinQuery(q)
        val key = if (pinyin) toneless(q) else engine.script.toSimplified(q)
        if (key.isEmpty()) return
        var hits = 0
        for (c in epub.chapters) {
            for ((bi, b) in epub.blocks(c.index).withIndex()) {
                coroutineContext.ensureActive()
                if (b !is EpubBlock.Text) continue
                // Matched in simplified form: a traditional book is found with simplified input and back.
                val simple = engine.script.toSimplified(b.text)
                val ranges = if (pinyin) pinyinMatches(simple, key, engine) else textMatches(simple, key)
                for (r in ranges) {
                    onHit(hit(c.index, c.title, bi, b.text, r.first, r.last + 1))
                    if (++hits >= MAX_HITS) return
                }
            }
            onProgress(c.index + 1, epub.chapters.size)
        }
    }

    fun textMatches(text: String, q: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val i = text.indexOf(q, from, ignoreCase = true)
            if (i < 0) break
            out += i until i + q.length
            from = i + q.length
        }
        return out
    }

    /**
     * Runs of consecutive characters whose toneless syllables, joined, equal [key]: "dianhua"
     * finds 电话 also inside 打电话. A character's syllable comes from its word's reading, so
     * polyphones read as in context (银行 háng).
     */
    fun pinyinMatches(text: String, key: String, engine: ZhEngine): List<IntRange> {
        if (text.none { it.isHan() }) return emptyList()
        val pos = ArrayList<Int>()
        val syl = ArrayList<String>()
        for (t in engine.segmenter.segment(text)) {
            if (t.kind != TokenKind.HAN) continue
            val parts = engine.lookup(t.text)?.pinyin?.split(' ')?.filter { it.isNotBlank() }
            for ((k, ch) in t.text.withIndex()) {
                val s = if (parts != null && parts.size == t.text.length) parts[k]
                else engine.lookup(ch.toString())?.pinyin?.substringBefore(' ').orEmpty()
                pos += t.start + k
                syl += toneless(s)
            }
        }
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < pos.size) {
            var acc = ""
            var j = i
            var matched = -1
            while (j < pos.size) {
                // Only characters next to each other in the text (no punctuation between) form one match.
                if (j > i && pos[j] != pos[j - 1] + 1) break
                acc += syl[j]
                if (acc == key) { matched = j; break }
                if (acc.length >= key.length || !key.startsWith(acc)) break
                j++
            }
            if (matched >= 0) {
                out += pos[i]..pos[matched]
                i = matched + 1
            } else i++
        }
        return out
    }

    private fun hit(chapter: Int, title: String, block: Int, text: String, start: Int, end: Int): SearchHit {
        val from = (start - CONTEXT).coerceAtLeast(0)
        val to = (end + CONTEXT).coerceAtMost(text.length)
        val prefix = if (from > 0) "…" else ""
        val body = text.substring(from, to).replace('\n', ' ')
        val snippet = prefix + body + (if (to < text.length) "…" else "")
        val s = prefix.length + (start - from)
        return SearchHit(chapter, title, block, start, end, snippet, s until s + (end - start))
    }
}
