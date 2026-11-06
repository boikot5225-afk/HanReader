package com.hanreader.app.reader

import android.content.Context
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.epub.BookSource
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhDictionary
import com.hanreader.app.zh.ZhEntry
import com.hanreader.app.zh.ZhSegmenter
import com.hanreader.app.zh.ZhToken
import com.hanreader.app.zh.isHan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class HskSystem(val title: String, val levels: List<Int>) {
    HSK3("HSK 3.0", listOf(1, 2, 3, 4, 5, 6, 7)),
    HSK2("HSK 2.0", listOf(1, 2, 3, 4, 5, 6));

    fun levelTitle(level: Int): String = if (this == HSK3 && level == 7) "7–9" else level.toString()
}

/** HSK levels per word: new HSK 3.0 (1..6, 7 = 7–9) and old HSK 2.0 (1..6); 0 = not listed. */
class HskLevels(private val levels: Map<String, Int>) {
    fun hsk3(word: String): Int = (levels[word] ?: 0) shr 4
    fun hsk2(word: String): Int = (levels[word] ?: 0) and 0xF

    fun wordsUpTo(system: HskSystem, level: Int): List<String> = levels.keys.filter {
        val l = if (system == HskSystem.HSK3) hsk3(it) else hsk2(it)
        l in 1..level
    }

    companion object {
        /**
         * The level of the other HSK system with about the same vocabulary size.
         * HSK 2.0 levels hold 150/300/600/1,200/2,500/5,000 words in total; HSK 3.0 levels
         * 506/1,256/2,209/3,181/4,240/5,363/~11,000.
         */
        fun equivalent(from: HskSystem, level: Int): Int = when (from) {
            HskSystem.HSK2 -> intArrayOf(0, 1, 1, 1, 2, 3, 5)[level.coerceIn(0, 6)]
            HskSystem.HSK3 -> intArrayOf(0, 2, 4, 4, 5, 5, 6, 6)[level.coerceIn(0, 7)]
        }

        /** assets/zh/hsk.tsv: word \t hsk3Level \t hsk2Level. */
        fun parse(lines: Sequence<String>): HskLevels {
            val map = HashMap<String, Int>(16_000)
            for (line in lines) {
                val p = line.split('\t')
                if (p.size < 3) continue
                val n = p[1].toIntOrNull() ?: 0
                val o = p[2].toIntOrNull() ?: 0
                map[p[0]] = (n shl 4) or o
            }
            return HskLevels(map)
        }
    }
}

/** Dictionary + segmenter, loaded once per process in the background. */
class ZhEngine(
    val dictionary: ZhDictionary,
    val loadMs: Long,
    val hsk: HskLevels = HskLevels(emptyMap()),
    /** Russian meanings from the Russian Wiktionary (about 11.7k words), senses joined by " ; ". */
    private val russian: Map<String, String> = emptyMap(),
    /** Machine translation of CC-CEDICT meanings into Russian, senses joined by "; ". */
    private val russianAi: Map<String, String> = emptyMap(),
    /** Proper names (assets/zh/names.tsv): p = person, l = place, o = organisation. */
    private val names: Map<String, Char> = emptyMap()
) {
    enum class NameKind(val title: String) { PERSON("человек"), PLACE("место"), ORGANISATION("организация") }

    /** Kind of a proper name from the names lexicon, or null for an ordinary word. */
    fun nameKind(word: String): NameKind? = when (names[word]) {
        'p' -> NameKind.PERSON
        'l' -> NameKind.PLACE
        'o' -> NameKind.ORGANISATION
        else -> null
    }

    enum class RussianSource(val label: String) {
        WIKTIONARY("Викисловарь"),
        AI("машинный перевод CC-CEDICT")
    }

    /** Where the Russian meaning of a word comes from, or null when there is none. */
    fun russianSource(word: String): RussianSource? = when {
        word in russian -> RussianSource.WIKTIONARY
        word in russianAi -> RussianSource.AI
        else -> null
    }

    /** Russian senses of a word: the Russian Wiktionary first, then the CC-CEDICT translation. */
    fun russianSenses(word: String): List<String> {
        russian[word]?.let { v -> return v.split(" ; ").map { it.trim() }.filter { it.isNotEmpty() } }
        return russianAi[word]?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }

    /**
     * The meaning stored on a flash card: Russian when available (first two senses), otherwise
     * the short English CC-CEDICT gloss, otherwise null.
     */
    fun cardTranslation(word: String): String? {
        val ru = russianSenses(word)
        if (ru.isNotEmpty()) return ru.take(2).joinToString("; ")
        return dictionary.lookup(word)?.takeIf { it.hasDefinition }?.shortGloss()
    }


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
        fun parseNames(lines: Sequence<String>): Map<String, Char> {
            val map = HashMap<String, Char>(80_000)
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab > 0 && tab + 1 < line.length) map[line.substring(0, tab)] = line[tab + 1]
            }
            return map
        }

        fun parseRussian(lines: Sequence<String>): Map<String, String> {
            val map = HashMap<String, String>(16_000)
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
            }
            return map
        }

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
                        val hsk = app.assets.open("zh/hsk.tsv").bufferedReader().useLines { HskLevels.parse(it) }
                        val ru = app.assets.open("zh/ru.tsv").bufferedReader().useLines { parseRussian(it) }
                        val ruAi = app.assets.open("zh/ru_ai.tsv").bufferedReader().useLines { parseRussian(it) }
                        val names = app.assets.open("zh/names.tsv").bufferedReader().useLines { parseNames(it) }
                        ZhEngine(dict, (System.nanoTime() - t) / 1_000_000, hsk, ru, ruAi, names)
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
    fun build(book: BookSource, index: Int, engine: ZhEngine, extra: Set<String> = emptySet()): ChapterContent {
        val t = System.nanoTime()
        var chars = 0
        val blocks = book.blocks(index).map { b ->
            when (b) {
                is EpubBlock.Image -> ReaderBlock.Image(b.path)
                is EpubBlock.Text -> {
                    chars += b.text.length
                    val tokens = engine.segmenter.segment(b.text, extra).map { tok ->
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

/** How much of a chapter the reader already knows, counted over word occurrences. */
data class KnownCoverage(val total: Int, val known: Int, val unknownDistinct: Int) {
    val percent: Int get() = if (total == 0) 0 else (known * 100 / total)

    companion object {
        fun of(chapter: ChapterContent, known: Set<String>): KnownCoverage = of(chapter, KnownMatcher(known, null))

        /** [names]: the book's names and «Не учить» words, left out of the count. */
        fun of(
            chapter: ChapterContent,
            matcher: KnownMatcher,
            names: Set<String> = emptySet(),
            engine: ZhEngine? = null
        ): KnownCoverage {
            var total = 0
            var k = 0
            val unknown = HashSet<String>()
            for (b in chapter.blocks) {
                if (b !is ReaderBlock.Text) continue
                val inNames = if (engine != null) VocabularyStats.nameTokens(b, names, engine) else emptySet()
                for ((i, t) in b.tokens.withIndex()) {
                    if (t.kind != TokenKind.HAN || i in inNames || t.text in names) continue
                    if (engine != null && VocabularyStats.isName(t.text, engine)) continue
                    total++
                    if (matcher.isKnown(t.text)) k++ else unknown += t.text
                }
            }
            return KnownCoverage(total, k, unknown.size)
        }
    }
}

/**
 * Words that HSK lists leave out because they follow from words on the list: 他 → 他们,
 * 那 + 些 → 那些, 一 + 个 → 一个, 没 + 有 → 没有. A derived word counts only when every part is
 * known and the combination is a real lexicon word. Deliberately narrow: 不 + X and other
 * combinations whose meaning can shift (不过, 东西) are not derived.
 */
object KnownExpansion {
    private const val PREFIXES = "这那哪一每几"
    private const val SUFFIXES = "个些里边儿样么种次天年位件本张家点下会"

    fun expand(base: Collection<String>, dict: ZhDictionary): Set<String> {
        val known = base as? Set<String> ?: base.toHashSet()
        val out = HashSet<String>()
        fun add(w: String) { if (w !in known && dict.contains(w)) out += w }
        for (w in known) {
            if (w.all { it.isHan() } && w.length <= 3) add(w + "们")
        }
        for (p in PREFIXES) {
            if (p.toString() !in known) continue
            for (x in SUFFIXES) if (x.toString() in known) add("$p$x")
        }
        if ("没" in known && "有" in known) add("没有")
        return out
    }
}

/**
 * Decides whether a token counts as known. Jieba's lexicon has many transparent compounds that
 * HSK lists never include (一个人, 看着, 走进, 打电话); a token outside the HSK lists is known
 * when it splits into known words and either has no dictionary meaning of its own (a Jieba-only
 * phrase) or is a known word plus a particle or direction/result complement (看 + 着, 走 + 进).
 * Idioms and other CC-CEDICT words whose meaning is not the sum of parts are never split.
 */
class KnownMatcher(private val known: Set<String>, private val engine: ZhEngine?) {
    private val cache = HashMap<String, Boolean>()

    fun isKnown(word: String): Boolean {
        if (word in known) return true
        val e = engine ?: return false
        if (word.length < 2) return false
        return cache.getOrPut(word) { composable(word, e) }
    }

    private fun composable(word: String, e: ZhEngine): Boolean {
        if (e.hsk.hsk3(word) > 0 || e.hsk.hsk2(word) > 0) return false // a listed word must be learned as such
        val defined = e.dictionary.lookup(word)?.hasDefinition == true
        if (!defined) return splits(word)
        if (word.length > 4) return false
        // known head + one or two complement characters (看着, 走进, 拿出来)
        for (tail in 1..2) {
            if (word.length - tail < 1) continue
            val head = word.substring(0, word.length - tail)
            val rest = word.substring(word.length - tail)
            if (head in known && rest.all { it in COMPLEMENTS } && rest.all { it.toString() in known }) return true
        }
        return false
    }

    /** True if [word] is a sequence of at least two known words. */
    private fun splits(word: String): Boolean {
        val n = word.length
        val ok = BooleanArray(n + 1).also { it[0] = true }
        val parts = IntArray(n + 1)
        for (i in 0 until n) {
            if (!ok[i]) continue
            for (j in i + 1..n) {
                if (j - i == n) continue // the whole word is not a split
                if (word.substring(i, j) in known) {
                    ok[j] = true
                    parts[j] = maxOf(parts[j], parts[i] + 1)
                }
            }
        }
        return ok[n] && parts[n] >= 2
    }

    /** Words of a chapter treated as known: the set the renderer hides pinyin for. */
    fun knownIn(chapter: ChapterContent): Set<String> {
        val out = HashSet<String>()
        for (b in chapter.blocks) {
            if (b !is ReaderBlock.Text) continue
            for (t in b.tokens) if (t.kind == TokenKind.HAN && isKnown(t.text)) out += t.text
        }
        return out
    }

    companion object {
        /** Aspect/structural particles and the common direction and result complements. */
        private const val COMPLEMENTS = "了着过的地得们来去上下进出回起开到完好见住走掉成"
    }
}
