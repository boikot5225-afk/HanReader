package com.hanreader.app.reader

import android.content.Context
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.epub.EpubBook
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhDictionary
import com.hanreader.app.zh.ZhEntry
import com.hanreader.app.zh.ZhSegmenter
import com.hanreader.app.zh.ZhToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Dictionary + segmenter, loaded once per process in the background. */
class ZhEngine(val dictionary: ZhDictionary, val loadMs: Long) {
    val segmenter = ZhSegmenter(dictionary)

    /** Word pinyin without spaces ("Zhōngguó"); for words without a CEDICT entry, built from characters. */
    fun pinyinOf(token: ZhToken): String {
        if (token.kind != TokenKind.HAN) return ""
        val p = token.entry?.pinyin.orEmpty()
        if (p.isNotEmpty()) return p.replace(" ", "")
        // Built from single characters: CC-CEDICT may list a character's surname reading first ("Zhāng"),
        // so lower-case it; inside an ordinary word it is the common reading.
        return token.text.map {
            dictionary.lookup(it.toString())?.pinyin?.substringBefore(' ').orEmpty().lowercase()
        }.joinToString("")
    }

    /** Dictionary entry for a word; for an unknown combination, null. */
    fun lookup(word: String): ZhEntry? = dictionary.lookup(word)

    /** Per-character breakdown for words the dictionary does not define. */
    fun characters(word: String): List<ZhEntry> =
        word.mapNotNull { dictionary.lookup(it.toString())?.takeIf { e -> e.hasDefinition } }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var deferred: CompletableDeferred<ZhEngine>? = null

        /** Starts loading if needed; await the result where the engine is required. */
        fun warmUp(context: Context): CompletableDeferred<ZhEngine> {
            deferred?.let { return it }
            synchronized(this) {
                deferred?.let { return it }
                val d = CompletableDeferred<ZhEngine>()
                deferred = d
                val app = context.applicationContext
                scope.launch {
                    runCatching {
                        val t = System.nanoTime()
                        val dict = app.assets.open("zh/lexicon.dat").use { ZhDictionary.load(it) }
                        ZhEngine(dict, (System.nanoTime() - t) / 1_000_000)
                    }.onSuccess { d.complete(it) }.onFailure { d.completeExceptionally(it) }
                }
                return d
            }
        }
    }
}

/** A token ready to draw: text, pinyin and whether it can be tapped (Han word). */
data class RToken(
    val text: String,
    val pinyin: String,
    val kind: TokenKind,
    val start: Int,
    val end: Int,
    val defined: Boolean
)

sealed interface ReaderBlock {
    data class Text(val text: String, val heading: Boolean, val tokens: List<RToken>) : ReaderBlock
    data class Image(val path: String) : ReaderBlock
}

data class ChapterContent(
    val index: Int,
    val title: String,
    val blocks: List<ReaderBlock>,
    val parseMs: Long,
    val chars: Int
)

object ChapterBuilder {
    fun build(book: EpubBook, index: Int, engine: ZhEngine): ChapterContent {
        val t = System.nanoTime()
        var chars = 0
        val blocks = book.blocks(index).map { b ->
            when (b) {
                is EpubBlock.Image -> ReaderBlock.Image(b.path)
                is EpubBlock.Text -> {
                    chars += b.text.length
                    val tokens = engine.segmenter.segment(b.text).map { tok ->
                        RToken(
                            text = tok.text,
                            pinyin = engine.pinyinOf(tok),
                            kind = tok.kind,
                            start = tok.start,
                            end = tok.end,
                            defined = tok.entry?.hasDefinition == true
                        )
                    }
                    ReaderBlock.Text(b.text, b.heading, tokens)
                }
            }
        }
        return ChapterContent(index, book.chapters[index].title, blocks, (System.nanoTime() - t) / 1_000_000, chars)
    }

    private const val SENTENCE_END = "。！？!?…；;\n"
    private const val MAX_SENTENCE = 140

    /** The sentence around [start, end) in a paragraph, trimmed to a readable length. */
    fun sentence(text: String, start: Int, end: Int): String {
        var s = start
        while (s > 0 && text[s - 1] !in SENTENCE_END) s--
        var e = end
        while (e < text.length && text[e] !in SENTENCE_END) e++
        if (e < text.length) e++
        // Keep a closing quote that belongs to the sentence.
        while (e < text.length && text[e] in "”’」』\"")  e++
        var out = text.substring(s, e).trim()
        if (out.length > MAX_SENTENCE) {
            val from = (start - s - MAX_SENTENCE / 2).coerceAtLeast(0)
            out = out.substring(from, (from + MAX_SENTENCE).coerceAtMost(out.length)).trim() + "…"
        }
        return out.trimStart('”', '’', '」', '』')
    }
}
