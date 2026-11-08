package com.hanreader.app.reader

import android.content.Context
import java.io.File
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.epub.BookSource
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhDictionary
import com.hanreader.app.zh.ZhScript
import com.hanreader.app.ScriptMode
import com.hanreader.app.zh.ZhEntry
import com.hanreader.app.zh.ZhSegmenter
import com.hanreader.app.zh.ZhToken
import com.hanreader.app.zh.isHan
import com.hanreader.app.lang.LanguageInfo
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.lang.Languages
import com.hanreader.app.lang.PreparedText
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.lang.WordLevels
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
                val a = line.indexOf('\t')
                val b = line.indexOf('\t', a + 1)
                if (a <= 0 || b < 0) continue
                val n = line.substring(a + 1, b).toIntOrNull() ?: 0
                val o = line.substring(b + 1).trim().toIntOrNull() ?: 0
                map[line.substring(0, a)] = (n shl 4) or o
            }
            return HskLevels(map)
        }
    }
}

/** The Chinese language module: dictionary + segmenter, loaded once per process in the background. */
class ZhEngine(
    val dictionary: ZhDictionary,
    override val loadMs: Long,
    val hsk: HskLevels = HskLevels(emptyMap()),
    /** Russian meanings from the Russian Wiktionary (about 11.7k words), senses joined by " ; ". */
    private val russian: Map<String, String> = emptyMap(),
    /** Machine translation of CC-CEDICT meanings into Russian, senses joined by "; ". */
    private val russianAi: Map<String, String> = emptyMap(),
    /** Proper names (assets/zh/names.tsv): p = person, l = place, o = organisation. */
    private val names: Map<String, Char> = emptyMap(),
    /** Simplified <-> traditional tables. */
    val script: ZhScript = ZhScript.NONE,
    /** Single characters by reading (assets/zh/ru_chars.tsv): 得 -> [dé: получить…, de: частица…], most frequent first. */
    private val russianChars: Map<String, List<Pair<String, String>>> = emptyMap()
) : LanguageModule {
    override val info: LanguageInfo get() = Languages.CHINESE

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
        AI("машинный перевод CC-CEDICT"),
        READING("машинный перевод CC-CEDICT, по чтению")
    }

    /** Where the Russian meaning of a word comes from, or null when there is none. */
    fun russianSource(word: String): RussianSource? = when {
        word in russianChars -> RussianSource.READING
        word in russian -> RussianSource.WIKTIONARY
        word in russianAi -> RussianSource.AI
        else -> null
    }

    /**
     * The entry of character [word] for [reading] (tone marks compared first, then without them),
     * else for the dictionary's reading, else the most frequent one. Null for words and unknown characters.
     */
    private fun charEntry(word: String, reading: String?): Pair<String, String>? {
        val list = russianChars[word] ?: return null
        fun pick(r: String?) = r?.lowercase()?.let { x ->
            list.firstOrNull { it.first.lowercase() == x } ?: list.firstOrNull { toneless(it.first) == toneless(x) }
        }
        return pick(reading) ?: pick(dictionary.lookup(word)?.pinyin?.substringBefore(' ')) ?: list.first()
    }

    /** The other readings of character [word] with their meanings ([reading] left out): "zhǎng — расти; глава". */
    fun otherReadings(word: String, reading: String?): List<Pair<String, String>> {
        val list = russianChars[word] ?: return emptyList()
        val here = charEntry(word, reading)
        return list.filter { it !== here }
    }

    /**
     * Russian senses of a word. A single character takes the meanings of the reading on the page
     * ([reading], e.g. 得 de after a verb), since a character's Wiktionary page mixes all its
     * readings. Words: the Russian Wiktionary first, then the CC-CEDICT translation.
     */
    fun russianSenses(word: String, reading: String? = null): List<String> {
        charEntry(word, reading)?.let { e -> return e.second.split(";").map { it.trim() }.filter { it.isNotEmpty() } }
        russian[word]?.let { v -> return v.split(" ; ").map { it.trim() }.filter { it.isNotEmpty() } }
        return russianAi[word]?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }

    private fun toneless(p: String): String = java.text.Normalizer.normalize(p.lowercase(), java.text.Normalizer.Form.NFD)
        .filter { it in 'a'..'z' }.replace("v", "u")

    /**
     * A word the dictionary does not define (a Jieba combination like 革命党 or 一件), split into
     * the fewest defined dictionary words, longest first: 革命 + 党. Null if the word has a meaning
     * of its own, is a single character, or cannot be covered by defined words.
     */
    fun compose(word: String): List<ZhEntry>? {
        if (word.length < 2 || dictionary.lookup(word)?.hasDefinition == true) return null
        val n = word.length
        val best = IntArray(n + 1) { Int.MAX_VALUE }
        val back = IntArray(n + 1)
        best[0] = 0
        for (i in 0 until n) {
            if (best[i] == Int.MAX_VALUE) continue
            for (len in minOf(n - i, dictionary.maxWordLength) downTo 1) {
                if (len == n) continue
                val part = word.substring(i, i + len)
                if (dictionary.lookup(part)?.hasDefinition != true) continue
                if (best[i] + 1 < best[i + len]) { best[i + len] = best[i] + 1; back[i + len] = len }
            }
        }
        if (best[n] == Int.MAX_VALUE) return null
        val parts = ArrayList<ZhEntry>()
        var k = n
        while (k > 0) { val len = back[k]; parts += dictionary.lookup(word.substring(k - len, k))!!; k -= len }
        return parts.reversed()
    }

    /** The first short sense of a defined word, Russian if possible. */
    private fun firstSense(word: String, reading: String? = null): String? {
        russianSenses(word, reading).firstNotNullOfOrNull { cleanSense(it) }?.let { return it }
        val e = dictionary.lookup(word)?.takeIf { it.hasDefinition } ?: return null
        return e.gloss.split("; ", "/").firstNotNullOfOrNull { s ->
            cleanSense(s.removePrefix("lit. ").removePrefix("to "))?.takeIf { !it.startsWith("variant of") && !it.startsWith("see ") }
        }
    }

    /**
     * The first part of a sense without remarks: brackets and leading labels such as «астрон.»,
     * «орнитол.», «перен. разг.» are dropped (a short note over the text has no room for them).
     */
    private fun cleanSense(x: String): String? = x.replace(Regex("\\([^)]*\\)|\\[[^]]*]|（[^）]*）"), " ")
        .trim().replace(Regex("^([а-яёa-z]{2,12}\\.\\s*)+", RegexOption.IGNORE_CASE), "")
        .split(',', ';', '；', '，').map { it.trim() }.firstOrNull { it.isNotEmpty() }

    /**
     * A character's meaning in a few words for the breakdown of a word: up to two short senses of
     * [reading] (the character's reading inside the word), so both senses of a merged character show
     * (须: «должен; борода»). Null if the character has no meaning.
     */
    fun charGloss(ch: String, reading: String? = null): String? {
        val senses = russianSenses(ch, reading).mapNotNull { cleanSense(it) }.distinct()
        val ru = senses.take(2).map { if (it.length > 14) it.take(13) + "…" else it }
        if (ru.isNotEmpty()) return ru.joinToString("; ")
        return firstSense(ch, reading)
    }

    /** The shortest of a word's first few senses: parts of a combination must fit side by side. */
    private fun shortSense(word: String): String? {
        val ru = russianSenses(word).take(3).mapNotNull { cleanSense(it) }
        return ru.minByOrNull { it.length } ?: firstSense(word)
    }

    /** "революция + партия" for a word made of dictionary words; null if it cannot be composed. */
    fun composedGloss(word: String): String? =
        compose(word)?.map { shortSense(it.word) ?: return null }?.joinToString(" + ")

    /**
     * A few words to write above an unknown word: the first Russian sense (first part, without
     * remarks in brackets or subject labels), else the first English sense; null if none.
     */
    override fun glossShort(word: String, reading: String?): String? = firstSense(word, reading) ?: composedGloss(word)

    /**
     * The meaning stored on a flash card: Russian when available (first two senses), otherwise
     * the short English CC-CEDICT gloss, otherwise null.
     */
    override fun cardTranslation(word: String, reading: String?): String? {
        val ru = russianSenses(word, reading)
        if (ru.isNotEmpty()) return ru.take(2).joinToString("; ")
        return dictionary.lookup(word)?.takeIf { it.hasDefinition }?.shortGloss() ?: composedGloss(word)
    }


    val segmenter = ZhSegmenter(dictionary)

    /**
     * A chapter in traditional script is segmented and looked up in simplified form (the
     * dictionary's), every conversion keeping offsets; [TextOptions.script] picks what is drawn.
     */
    override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText {
        val traditional = script.looksTraditional(paragraphs)
        return { raw ->
            val simple = if (traditional) script.toSimplified(raw) else raw
            val shown = when (options.script) {
                ScriptMode.AS_BOOK -> raw
                ScriptMode.SIMPLIFIED -> simple
                ScriptMode.TRADITIONAL -> if (traditional) raw else script.toTraditional(simple)
            }.takeIf { it.length == simple.length } ?: simple
            val segmented = segmenter.segment(simple, options.extra, options.blocked)
            val tokens = segmented.mapIndexed { k, tok ->
                // A standalone polyphone reads as its neighbours say (慢慢地 de, 很长 cháng).
                val contextual = com.hanreader.app.zh.Polyphones.reading(segmented, k)
                RToken(
                    text = tok.text,
                    pinyin = contextual ?: pinyinOf(tok),
                    kind = tok.kind,
                    start = tok.start,
                    end = tok.end,
                    defined = tok.entry?.hasDefinition == true,
                    display = if (shown === simple) tok.text else shown.substring(tok.start, tok.end),
                    syllables = contextual?.let { listOf(it) } ?: syllablesOf(tok)
                )
            }
            PreparedText(simple, shown, tokens)
        }
    }

    override fun reading(word: String): String? = dictionary.lookup(word)?.pinyin

    override fun studyable(word: String): Boolean = VocabularyStats.studyable(word, dictionary)

    /** A proper name outside the HSK lists: in the names lexicon, or a CC-CEDICT reading with a capital. */
    override fun isName(word: String): Boolean {
        if (hsk.hsk3(word) > 0 || hsk.hsk2(word) > 0) return false
        if (nameKind(word) != null) return true
        return dictionary.lookup(word)?.pinyin?.firstOrNull()?.isUpperCase() == true
    }

    override fun nameCandidates(tokens: List<RToken>): List<IntRange> =
        NameRuns.runs(tokens, { it.text }, { it.kind == TokenKind.HAN }, dictionary)

    override fun sureNames(tokens: List<RToken>): List<IntRange> =
        DotNames.runs(tokens, { it.text }, { it.kind == TokenKind.HAN }, dictionary)

    /** HSK 3.0 (1..7, 7 = 7–9). HSK 2.0, chosen in the library, is mapped onto it. */
    override val levels: WordLevels = object : WordLevels {
        override val name = "HSK"
        override val scaleTitle = "HSK 3.0"
        override val all = HskSystem.HSK3.levels
        override fun title(level: Int) = HskSystem.HSK3.levelTitle(level)
        override fun of(word: String) = hsk.hsk3(word)

        /** Words outside the lists: a level guessed from general frequency. */
        override fun estimate(word: String): Int {
            hsk.hsk3(word).takeIf { it > 0 }?.let { return it }
            val f = dictionary.freq(word)
            return when {
                f >= 20_000 -> 2
                f >= 5_000 -> 4
                f >= 1_000 -> 6
                f >= 200 -> 7
                else -> 8
            }
        }

        override fun knownAt(level: Int): Set<String> {
            val listed = hsk.wordsUpTo(HskSystem.HSK3, level).toHashSet()
            return listed + KnownExpansion.expand(listed, dictionary)
        }
    }

    /**
     * Jieba's lexicon has many transparent compounds that HSK lists never include (一个人, 看着,
     * 走进, 打电话); a token outside the HSK lists is known when it splits into known words and
     * either has no dictionary meaning of its own (a Jieba-only phrase) or is a known word plus a
     * particle or direction/result complement (看 + 着, 走 + 进). Idioms and other CC-CEDICT words
     * whose meaning is not the sum of parts are never split.
     */
    override fun knownByParts(word: String, known: Set<String>): Boolean {
        if (word.length < 2) return false
        if (hsk.hsk3(word) > 0 || hsk.hsk2(word) > 0) return false // a listed word must be learned as such
        val defined = dictionary.lookup(word)?.hasDefinition == true
        if (!defined) return splits(word, known)
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
    private fun splits(word: String, known: Set<String>): Boolean {
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

    override fun expandKnown(base: Collection<String>): Set<String> = KnownExpansion.expand(base, dictionary)

    /** Matched in simplified form: a traditional book is found with simplified input and back. */
    override fun searchForm(text: String): String = script.toSimplified(text)

    override fun isReadingQuery(query: String): Boolean = BookSearch.isPinyinQuery(query)

    override fun readingMatches(text: String, query: String): List<IntRange> {
        val key = BookSearch.toneless(query)
        return if (key.isEmpty()) emptyList() else BookSearch.pinyinMatches(text, key, this)
    }

    /** Also finds the book's transliterated names and names next to a middle dot. */
    override fun analyze(bookId: Long, book: BookSource, onProgress: (Int, Int) -> Unit): BookAnalysis =
        BookAnalyzer.analyze(bookId, book, segmenter, dictionary, script, onProgress)

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

    /** One reading per character of a Han token (for tone colours); empty if they do not line up. */
    fun syllablesOf(token: ZhToken): List<String> {
        if (token.kind != TokenKind.HAN) return emptyList()
        val p = token.entry?.pinyin.orEmpty()
        val parts = if (p.isNotEmpty()) p.split(' ').filter { it.isNotEmpty() }
        else token.text.map { dictionary.lookup(it.toString())?.pinyin?.substringBefore(' ').orEmpty().lowercase() }
        return if (parts.size == token.text.length && parts.none { it.isEmpty() }) parts else emptyList()
    }

    /** Dictionary entry for a word; for an unknown combination, null. */
    fun lookup(word: String): ZhEntry? = dictionary.lookup(word)

    /** Per-character breakdown for words the dictionary does not define. */
    fun characters(word: String): List<ZhEntry> =
        word.mapNotNull { dictionary.lookup(it.toString())?.takeIf { e -> e.hasDefinition } }

    companion object {
        /** Aspect/structural particles and the common direction and result complements. */
        private const val COMPLEMENTS = "了着过的地得们来去上下进出回起开到完好见住走掉成"

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
                if (tab <= 0) continue
                // Character pages of the Wiktionary sometimes give reference data instead of a meaning
                // ("ключ 109 目+7 черт, метод ввода cangjie…"): those senses are left out.
                val senses = line.substring(tab + 1).split(" ; ").filter { !REFERENCE.containsMatchIn(it) }
                if (senses.isNotEmpty()) map[line.substring(0, tab)] = senses.joinToString(" ; ")
            }
            return map
        }

        private val REFERENCE = Regex("ключ \\d+|\\d+ черт|cangjie|метод ввода|четырёхугольник", RegexOption.IGNORE_CASE)

        /** assets/zh/ru_chars.tsv: character \t reading \t senses, readings in order of frequency. */
        fun parseRussianChars(lines: Sequence<String>): Map<String, List<Pair<String, String>>> {
            val map = HashMap<String, MutableList<Pair<String, String>>>(8_000)
            for (line in lines) {
                val p = line.split('\t')
                if (p.size == 3 && p[0].isNotEmpty() && p[1].isNotEmpty() && p[2].isNotEmpty()) map.getOrPut(p[0]) { ArrayList(2) } += p[1] to p[2]
            }
            return map
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Starts loading if needed; await the result where the engine is required. */
        @Volatile private var full: CompletableDeferred<ZhEngine>? = null
        @Volatile private var core: CompletableDeferred<ZhEngine>? = null

        /** The complete engine: dictionary plus Russian meanings, HSK levels and names. */
        fun warmUp(context: Context): CompletableDeferred<ZhEngine> { start(context); return full!! }

        /** The dictionary alone, ready in milliseconds: enough to segment and show a chapter. */
        fun core(context: Context): CompletableDeferred<ZhEngine> { start(context); return core!! }

        private fun start(context: Context) {
            if (full != null) return
            synchronized(this) {
                if (full != null) return
                val c = CompletableDeferred<ZhEngine>()
                val f = CompletableDeferred<ZhEngine>()
                core = c
                full = f
                val app = context.applicationContext
                scope.launch {
                    try {
                        val t = System.nanoTime()
                        val dict = ZhDictionary.map(lexiconFile(app))
                        val ms = (System.nanoTime() - t) / 1_000_000
                        val script = runCatching { app.assets.open("zh/script.tsv").use { ZhScript.parse(it) } }.getOrDefault(ZhScript.NONE)
                        c.complete(ZhEngine(dict, ms, script = script))
                        val hsk = app.assets.open("zh/hsk.tsv").bufferedReader().useLines { HskLevels.parse(it) }
                        val ru = app.assets.open("zh/ru.tsv").bufferedReader().useLines { parseRussian(it) }
                        val ruAi = app.assets.open("zh/ru_ai.tsv").bufferedReader().useLines { parseRussian(it) }
                        val names = app.assets.open("zh/names.tsv").bufferedReader().useLines { parseNames(it) }
                        val ruChars = runCatching { app.assets.open("zh/ru_chars.tsv").bufferedReader().useLines { parseRussianChars(it) } }.getOrDefault(emptyMap())
                        f.complete(ZhEngine(dict, ms, hsk, ru, ruAi, names, script, ruChars))
                    } catch (e: Throwable) {
                        c.completeExceptionally(e)
                        f.completeExceptionally(e)
                    }
                }
            }
        }

        /**
         * The binary lexicon, copied once from the APK (where it is compressed) into app storage,
         * so it can be mapped into memory. A new install or update makes a fresh copy.
         */
        private fun lexiconFile(app: Context): File {
            val stamp = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            val dir = File(app.filesDir, "lexicon").apply { mkdirs() }
            val file = File(dir, "lexicon-$stamp.bin")
            if (file.exists() && file.length() > 0) return file
            dir.listFiles()?.forEach { it.delete() }
            val tmp = File(dir, "copy.tmp")
            app.assets.open("zh/lexicon.bin").use { src -> tmp.outputStream().use { src.copyTo(it, 1 shl 16) } }
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
            return file
        }
    }
}

/**
 * Words the reader knows, plus the ones marked «Не знаю» ([unknown]): those stay unknown even
 * when the level, learned cards or known parts would count them.
 */
class KnownWords(private val words: Set<String>, val unknown: Set<String>) : Set<String> by words

/** A token ready to draw: text, pinyin and whether it can be tapped (Han word). */
data class RToken(
    val text: String,
    val pinyin: String,
    val kind: TokenKind,
    val start: Int,
    val end: Int,
    val defined: Boolean,
    /** What is drawn: [text] in the script the reader chose (traditional, as in the book). */
    val display: String = text,
    /** The reading of each character, in order; empty when unknown. Joined, they make [pinyin]. */
    val syllables: List<String> = emptyList()
)

sealed interface ReaderBlock {
    /** [text]: simplified (dictionary, search, cards); [shown]: as drawn, same length. */
    data class Text(val text: String, val heading: Boolean, val tokens: List<RToken>, val shown: String = text) : ReaderBlock
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
    /**
     * Builds a chapter through the language module ([engine]): it splits each paragraph into words
     * and writes the line above them. [script]: Chinese only, the characters to draw.
     */
    fun build(
        book: BookSource,
        index: Int,
        engine: LanguageModule,
        extra: Set<String> = emptySet(),
        script: ScriptMode = ScriptMode.AS_BOOK,
        /** Words the reader split: never kept whole. */
        blocked: Set<String> = emptySet()
    ): ChapterContent {
        val t = System.nanoTime()
        var chars = 0
        val source = book.blocks(index)
        val prepare = engine.chapter(source.mapNotNull { (it as? EpubBlock.Text)?.text }, TextOptions(extra, blocked, script))
        val blocks = source.map { b ->
            when (b) {
                is EpubBlock.Image -> ReaderBlock.Image(b.path)
                is EpubBlock.Text -> {
                    chars += b.text.length
                    val p = prepare(b.text)
                    ReaderBlock.Text(p.text, b.heading, p.tokens, p.shown)
                }
            }
        }
        return ChapterContent(index, book.chapters[index].title, blocks, (System.nanoTime() - t) / 1_000_000, chars)
    }

    private val SENTENCE_END get() = Languages.current.sentenceEnd
    private const val MAX_SENTENCE = 140

    /** The sentence around [start, end) in a paragraph, trimmed to a readable length. */
    fun sentence(text: String, start: Int, end: Int): String {
        var s = start
        // A full stop after an abbreviation (Sr. Ortega) does not end the sentence.
        while (s > 0 && !(text[s - 1] in SENTENCE_END && Sentences.ends(text, s - 1))) s--
        var e = end
        while (e < text.length && !(text[e] in SENTENCE_END && Sentences.ends(text, e))) e++
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
            engine: LanguageModule? = null
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
 * Decides whether a token counts as known: in the known set, or following from known words as
 * the language module says ([LanguageModule.knownByParts]; Chinese: 一个人, 看着, 走进).
 */
class KnownMatcher(private val known: Set<String>, private val engine: LanguageModule?) {
    private val cache = HashMap<String, Boolean>()
    private val unknown: Set<String> = (known as? KnownWords)?.unknown ?: emptySet()

    fun isKnown(word: String): Boolean {
        if (word in unknown) return false
        if (word in known) return true
        val e = engine ?: return false
        return cache.getOrPut(word) { e.knownByParts(word, known) }
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
}
