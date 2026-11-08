package com.hanreader.app.es

import android.content.Context
import com.hanreader.app.lang.LanguageInfo
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.lang.Languages
import com.hanreader.app.lang.PreparedText
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.reader.RToken
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A dictionary word: [ru] from the Russian Wiktionary, [en] from the English one. */
data class EsEntry(
    val lemma: String,
    val pos: String,
    val ru: List<String>,
    val en: List<String>,
    /** The Russian meaning is a machine translation of the English one (not the Russian Wiktionary). */
    val machine: Boolean = false,
    /** Frequency rank in Spanish subtitles: 1 = most frequent, 0 = not in the list. */
    val rank: Int = 0
) {
    val isName: Boolean get() = pos == "имя"
}

/** One reading of a word on the page: hablo → hablar, «1 л. ед. ч., наст.». */
data class EsAnalysis(val form: String, val entry: EsEntry, val grammar: String)

/**
 * The Spanish language module. Words are runs of letters; a word on the page is looked up as it
 * is, then as a form of a dictionary word (hablo → hablar, fue → ir / ser), then with the
 * pronouns attached to a verb taken off (dámelo → dar + me + lo).
 */
class EsEngine(
    private val dict: SortedTsv,
    private val forms: SortedTsv,
    private val grammar: List<String>,
    override val loadMs: Long = 0
) : LanguageModule {
    override val info: LanguageInfo get() = Languages.SPANISH

    private val cache = object : LinkedHashMap<String, List<EsAnalysis>>(2048, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EsAnalysis>>?) = size > 20_000
    }

    fun entry(lemma: String): EsEntry? {
        val f = dict.get(lemma) ?: return null
        fun senses(i: Int) = f.getOrNull(i).orEmpty().split(" ; ").map { it.trim() }.filter { it.isNotEmpty() }
        return EsEntry(lemma, f.getOrNull(0).orEmpty(), senses(1), senses(2), machine = f.getOrNull(3) == "a", rank = f.getOrNull(4)?.toIntOrNull() ?: 0)
    }

    /** Every dictionary reading of [word], the most useful first (a word with a Russian meaning first). */
    fun analyses(word: String): List<EsAnalysis> {
        val w = word.lowercase()
        synchronized(cache) { cache[w] }?.let { return it }
        val self = entry(w)?.let { EsAnalysis(w, it, "") }
        val asForm = ArrayList<EsAnalysis>(2)
        // "!": a frequent verb form that is also a rare word (era: ser before «эра»).
        val f = forms.get(w)?.firstOrNull().orEmpty()
        val formFirst = f.startsWith("!")
        f.removePrefix("!").split('|').filter { it.isNotEmpty() }.forEach { item ->
            val lemma = item.substringBefore(':')
            val g = item.substringAfter(':', "0").toIntOrNull()?.let { grammar.getOrNull(it) }.orEmpty()
            if (lemma != w && asForm.none { it.entry.lemma == lemma }) entry(lemma)?.let { asForm += EsAnalysis(w, it, g) }
        }
        val out = ArrayList<EsAnalysis>(3)
        if (formFirst) { out += asForm; self?.let { out += it } } else { self?.let { out += it }; out += asForm }
        if (out.isEmpty()) out += withoutPronouns(w)
        // A reading with a Russian meaning first, unless the order was set on purpose ("!").
        val sorted = if (formFirst) out else out.sortedBy { if (it.entry.ru.isNotEmpty()) 0 else 1 }
        synchronized(cache) { cache[w] = sorted }
        return sorted
    }

    /** dámelo → dar, «+ местоим.»: up to two attached pronouns, the stress mark they forced dropped. */
    private fun withoutPronouns(w: String): List<EsAnalysis> {
        for (p1 in PRONOUNS) {
            if (!w.endsWith(p1) || w.length - p1.length < 2) continue
            val one = w.dropLast(p1.length)
            verb(one)?.let { return listOf(it) }
            for (p2 in PRONOUNS) {
                if (!one.endsWith(p2) || one.length - p2.length < 2) continue
                verb(one.dropLast(p2.length))?.let { return listOf(it) }
            }
        }
        return emptyList()
    }

    /** A verb form (infinitive, gerund, imperative) under [stem], with or without its stress mark. */
    private fun verb(stem: String): EsAnalysis? {
        for (s in listOf(stem, unaccent(stem)).distinct()) {
            entry(s)?.takeIf { it.pos == "гл." }?.let { return EsAnalysis(stem, it, "+ местоим.") }
            forms.get(s)?.firstOrNull()?.split('|')?.forEach { item ->
                val lemma = item.substringBefore(':')
                entry(lemma)?.takeIf { it.pos == "гл." }?.let { e ->
                    val g = item.substringAfter(':', "0").toIntOrNull()?.let { grammar.getOrNull(it) }.orEmpty()
                    return EsAnalysis(stem, e, listOf(g, "+ местоим.").filter { it.isNotEmpty() }.joinToString(", "))
                }
            }
        }
        return null
    }

    /**
     * The longest dictionary phrase of 2–4 words around the word at [index] (sin embargo, a pesar de),
     * as a range of token indices; words must be separated by single spaces. Null if none.
     */
    fun phraseAt(tokens: List<RToken>, text: String, index: Int): IntRange? {
        if (tokens.getOrNull(index)?.kind != TokenKind.HAN) return null
        // Word tokens reachable from [index] through single spaces, up to three on each side.
        fun step(from: Int, dir: Int): Int? {
            val sp = tokens.getOrNull(from + dir) ?: return null
            if (sp.kind != TokenKind.SPACE || sp.text != " ") return null
            return (from + 2 * dir).takeIf { tokens.getOrNull(it)?.kind == TokenKind.HAN }
        }
        val left = generateSequence(index) { step(it, -1) }.take(4).toList()
        val right = generateSequence(index) { step(it, 1) }.take(4).toList()
        var best: IntRange? = null
        for (a in left) for (b in right) {
            val words = (b - a) / 2 + 1
            if (words < 2 || words > 4 || (best != null && words <= (best.last - best.first) / 2 + 1)) continue
            if (dict.contains(text.substring(tokens[a].start, tokens[b].end).lowercase())) best = a..b
        }
        return best
    }

    /** The dictionary word of [word] (hablo → hablar); the word itself, lower-case, if unknown. */
    override fun lemma(word: String): String = analyses(word).firstOrNull()?.entry?.lemma ?: word.lowercase()

    override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText = { text ->
        PreparedText(text, text, tokenize(text))
    }

    fun tokenize(text: String): List<RToken> {
        val out = ArrayList<RToken>(text.length / 4 + 4)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val start = i
            val kind = when {
                c.isLetter() -> {
                    // A word may hold an apostrophe or a hyphen between letters (O'Donnell, franco-español).
                    while (i < text.length && (text[i].isLetter() || (text[i] in "'’-" && i + 1 < text.length && text[i + 1].isLetter() && i > start))) i++
                    TokenKind.HAN
                }
                c.isWhitespace() -> { while (i < text.length && text[i].isWhitespace()) i++; TokenKind.SPACE }
                c.isDigit() -> { while (i < text.length && text[i].isLetterOrDigit()) i++; TokenKind.OTHER }
                else -> { i++; TokenKind.PUNCT }
            }
            val t = text.substring(start, i)
            out += RToken(t, "", kind, start, i, kind == TokenKind.HAN && analyses(t).isNotEmpty())
        }
        return out
    }

    override fun glossShort(word: String, reading: String?): String? {
        val e = analyses(word).firstOrNull()?.entry ?: return null
        (e.ru.firstOrNull() ?: e.en.firstOrNull()?.removePrefix("to "))?.let { return shortSense(it) }
        return null
    }

    override fun cardTranslation(word: String, reading: String?): String? {
        val e = analyses(word).firstOrNull()?.entry ?: return null
        return (e.ru.ifEmpty { e.en }).take(2).joinToString("; ").ifEmpty { null }
    }

    override fun studyable(word: String): Boolean = analyses(word).firstOrNull()?.entry?.isName == false

    /** A name of the dictionary (Sinaloa), or a capitalised word it does not know (Blancornelas). */
    override fun isName(word: String): Boolean {
        val a = analyses(word)
        if (a.isEmpty()) return word.firstOrNull()?.isUpperCase() == true
        return a.first().entry.isName
    }

    /** Words counted by dictionary form (habló and hablaban as hablar); unknown words as written. */
    override fun analyze(bookId: Long, book: com.hanreader.app.epub.BookSource, onProgress: (Int, Int) -> Unit): com.hanreader.app.reader.BookAnalysis {
        val counts = HashMap<String, IntArray>()
        var tokens = 0
        for (c in book.chapters) {
            for (b in book.blocks(c.index)) {
                val text = (b as? com.hanreader.app.epub.EpubBlock.Text)?.text ?: continue
                for (t in tokenize(text)) {
                    if (t.kind != TokenKind.HAN) continue
                    tokens++
                    val key = analyses(t.text).firstOrNull()?.entry?.lemma ?: t.text
                    counts.getOrPut(key) { intArrayOf(0, c.index) }[0]++
                }
            }
            onProgress(c.index + 1, book.chapters.size)
        }
        return com.hanreader.app.reader.BookAnalysis(counts.map { (w, e) -> com.hanreader.app.BookWord(bookId, w, e[0], e[1]) }, tokens)
    }

    /** A form counts as known when its dictionary word is known (habló, if hablar is known). */
    override fun knownByParts(word: String, known: Set<String>): Boolean =
        word.lowercase() in known || analyses(word).any { it.entry.lemma in known }

    /**
     * Levels A1–C2 by frequency, not an official list: the most frequent 500 words count as A1,
     * up to 1 500 A2, 3 000 B1, 5 000 B2, 8 000 C1, 16 000 C2 (ranks of FrequencyWords).
     */
    override val levels: com.hanreader.app.lang.WordLevels = object : com.hanreader.app.lang.WordLevels {
        private val bands = intArrayOf(500, 1_500, 3_000, 5_000, 8_000, 16_000)
        private val titles = listOf("A1", "A2", "B1", "B2", "C1", "C2")
        private val byLevel: List<Set<String>> by lazy {
            val sets = List(bands.size) { HashSet<String>() }
            dict.forEach { lemma, f ->
                val r = f.getOrNull(4)?.toIntOrNull() ?: 0
                if (r > 0) levelOf(r).takeIf { it > 0 }?.let { sets[it - 1] += lemma }
            }
            sets
        }
        private fun levelOf(rank: Int): Int = bands.indexOfFirst { rank <= it } + 1

        override val name = ""
        override val scaleTitle = "уровней A1–C2 (по частотности слов)"
        override val all = (1..bands.size).toList()
        override fun title(level: Int) = titles.getOrElse(level - 1) { "C2+" }
        override fun of(word: String): Int = analyses(word).firstOrNull()?.entry?.rank?.takeIf { it > 0 }?.let { levelOf(it) } ?: 0
        override fun estimate(word: String): Int = of(word).takeIf { it > 0 } ?: (bands.size + 1)
        override fun knownAt(level: Int): Set<String> = (1..level.coerceAtMost(bands.size)).flatMapTo(HashSet()) { byLevel[it - 1] }
    }

    /** Searched without case and stress marks: «cancion» finds «canción». */
    override fun searchForm(text: String): String = unaccent(text.lowercase())

    companion object {
        private val PRONOUNS = listOf("nos", "los", "las", "les", "me", "te", "se", "os", "lo", "la", "le")

        /** Drops stress marks, keeping the length (ñ and ü stay). */
        fun unaccent(s: String): String = buildString(s.length) {
            for (c in s) append(when (c) { 'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú' -> 'u'
                'Á' -> 'A'; 'É' -> 'E'; 'Í' -> 'I'; 'Ó' -> 'O'; 'Ú' -> 'U'; else -> c })
        }

        /** The first part of a sense: brackets and everything after a comma or semicolon dropped. */
        fun shortSense(x: String): String? = x.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .split(',', ';').map { it.trim() }.firstOrNull { it.isNotEmpty() }

        fun load(dict: java.io.InputStream, forms: java.io.InputStream, grammar: java.io.InputStream): EsEngine {
            val t = System.nanoTime()
            val d = SortedTsv.read(dict)
            val f = SortedTsv.read(forms)
            val g = grammar.bufferedReader().use { it.readLines() }
            return EsEngine(d, f, g, (System.nanoTime() - t) / 1_000_000)
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var loading: CompletableDeferred<EsEngine>? = null

        /** Loads the dictionary once per process in the background (about 18 MB of text). */
        fun warmUp(context: Context): CompletableDeferred<EsEngine> {
            loading?.let { return it }
            synchronized(this) {
                loading?.let { return it }
                val d = CompletableDeferred<EsEngine>()
                loading = d
                val app = context.applicationContext
                scope.launch {
                    runCatching {
                        load(app.assets.open("es/dict.tsv"), app.assets.open("es/forms.tsv"), app.assets.open("es/grammar.tsv"))
                    }.fold({ d.complete(it) }, { d.completeExceptionally(it) })
                }
                return d
            }
        }
    }
}
