package com.hanreader.app.reader

import com.hanreader.app.BookWord
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.epub.BookSource
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhDictionary
import com.hanreader.app.zh.ZhSegmenter

/** Word counts of a whole book: the result of the one-time analysis. */
data class BookAnalysis(val words: List<BookWord>, val tokens: Int)

/**
 * Transliterated foreign names: the segmenter splits 德思礼-like names into single characters,
 * which then look like the book's most frequent unknown words. A name is a run of two or more
 * single-character tokens drawn from the usual transliteration characters, not a lexicon word,
 * repeated at least [MIN_REPEATS] times in the book.
 */
object NameRuns {
    const val MIN_REPEATS = 3
    private const val CHARS =
        "阿埃艾爱安昂奥巴拜班邦保鲍贝本比彼毕庇波伯博布查达戴丹道德登迪狄蒂丁东杜敦多厄恩尔法范菲费芬丰" +
        "弗福盖甘冈高戈哥格根古瓜哈海亥汉豪赫黑亨洪胡华霍基吉加嘉贾杰金卡凯坎康考柯科克肯库夸奎拉莱兰朗劳" +
        "乐勒雷里利莉丽莲林琳留卢鲁路伦罗洛马玛迈麦曼梅美门蒙米敏缪莫默姆穆纳娜奈南内尼妮宁纽努诺欧帕派潘" +
        "庞佩彭皮珀普奇齐恰乔琴琼丘萨塞赛桑瑟森沙莎尚舍什史士斯松苏索塔泰坦汤唐特提廷托瓦旺威韦维温文沃乌" +
        "西希锡谢辛休许雅亚扬耶伊因尤约泽扎詹珍兹佐"

    fun isNameChar(c: Char) = c in CHARS

    /** Token index ranges of name-like runs in one paragraph (repetition is checked by the caller). */
    fun <T> runs(tokens: List<T>, text: (T) -> String, isHan: (T) -> Boolean, dict: ZhDictionary): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < tokens.size) {
            var j = i
            while (j < tokens.size && isHan(tokens[j]) && text(tokens[j]).length == 1 && isNameChar(text(tokens[j])[0])) j++
            if (j - i >= 2) {
                val run = (i until j).joinToString("") { text(tokens[it]) }
                if (!dict.contains(run)) out += i until j
            }
            i = maxOf(j, i + 1)
        }
        return out
    }
}

/**
 * Full foreign names in Chinese translations are written with a middle dot: 名·姓. The words
 * right next to the dot are parts of a name even when they occur only once: runs of single
 * characters or of words the dictionary does not define, up to [MAX_PART] characters a side.
 */
object DotNames {
    private const val DOTS = "·・•‧"
    private const val MAX_PART = 5

    fun <T> runs(
        tokens: List<T>,
        text: (T) -> String,
        isHan: (T) -> Boolean,
        dict: ZhDictionary
    ): List<IntRange> {
        fun namePart(t: T): Boolean {
            if (!isHan(t)) return false
            val w = text(t)
            return w.length == 1 || dict.lookup(w)?.hasDefinition != true
        }
        val out = ArrayList<IntRange>()
        for ((i, t) in tokens.withIndex()) {
            val w = text(t)
            if (w.length != 1 || w[0] !in DOTS) continue
            var a = i
            var chars = 0
            while (a - 1 >= 0 && namePart(tokens[a - 1]) && chars + text(tokens[a - 1]).length <= MAX_PART) {
                a--; chars += text(tokens[a]).length
            }
            if (a < i && chars >= 2) out += a until i
            var b = i
            chars = 0
            while (b + 1 < tokens.size && namePart(tokens[b + 1]) && chars + text(tokens[b + 1]).length <= MAX_PART) {
                b++; chars += text(tokens[b]).length
            }
            if (b > i && chars >= 2) out += (i + 1)..b
        }
        return out
    }
}

object BookAnalyzer {
    /** Bumped when the analysis finds more (2: names next to a middle dot). Older books are re-analysed. */
    const val VERSION = 2

    /**
     * Segments every chapter once and counts Han words. Name runs repeated in the book are stored
     * as ignored words, and their characters are not counted on their own. [onProgress] gets
     * (done, total) chapters. About 2 s per million characters on a desktop JVM.
     */
    fun analyze(
        bookId: Long,
        epub: BookSource,
        segmenter: ZhSegmenter,
        dict: ZhDictionary,
        script: com.hanreader.app.zh.ZhScript = com.hanreader.app.zh.ZhScript.NONE,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): BookAnalysis {
        val counts = HashMap<String, IntArray>(32_000) // [count, firstChapter]
        val runs = HashMap<String, IntArray>()          // [count, firstChapter]
        val dotNames = HashSet<String>()
        var tokens = 0
        val total = epub.chapters.size
        for (c in epub.chapters) {
            val texts = script.forDictionary(epub.blocks(c.index).mapNotNull { (it as? EpubBlock.Text)?.text })
            for (text in texts) {
                val seg = segmenter.segment(text)
                for (t in seg) {
                    if (t.kind != TokenKind.HAN) continue
                    tokens++
                    counts.getOrPut(t.text) { intArrayOf(0, c.index) }[0]++
                }
                for (r in NameRuns.runs(seg, { it.text }, { it.kind == TokenKind.HAN }, dict)) {
                    val name = r.joinToString("") { seg[it].text }
                    runs.getOrPut(name) { intArrayOf(0, c.index) }[0]++
                }
                // Names next to a middle dot count from the first occurrence.
                for (r in DotNames.runs(seg, { it.text }, { it.kind == TokenKind.HAN }, dict)) {
                    val name = r.joinToString("") { seg[it].text }
                    val e = runs.getOrPut(name) { intArrayOf(0, c.index) }
                    e[0]++
                    if (e[0] < NameRuns.MIN_REPEATS) dotNames += name
                }
            }
            onProgress(c.index + 1, total)
        }
        val names = ArrayList<BookWord>()
        for ((name, e) in runs) {
            if (e[0] < NameRuns.MIN_REPEATS && name !in dotNames) continue
            names += BookWord(bookId, name, e[0], e[1], ignored = true)
            for (ch in name) counts[ch.toString()]?.let { it[0] -= e[0] }
            tokens -= e[0] * name.length
        }
        val words = counts.filterValues { it[0] > 0 }.map { (w, e) -> BookWord(bookId, w, e[0], e[1]) }
        return BookAnalysis(words + names, tokens)
    }
}

/** A word worth studying before reading: frequent, not known, not already in the cards. */
data class StudyCandidate(
    val word: String,
    val count: Int,
    val pinyin: String,
    val gloss: String,
    val firstChapter: Int
)

/** Share of the text covered when every word up to an HSK 3.0 level is known. */
data class LevelCoverage(val level: Int, val percent: Int)

data class BookStats(
    val tokens: Int,
    val distinct: Int,
    val knownPercent: Int,
    val unknownDistinct: Int,
    val levels: List<LevelCoverage>,
    val candidates: List<StudyCandidate>
) {
    /** The lowest HSK 3.0 level whose words cover at least [target]% of the text, or null. */
    fun levelFor(target: Int): Int? = levels.firstOrNull { it.percent >= target }?.level
}

object VocabularyStats {
    /**
     * Words not worth suggesting: no definition (names the lexicon only knows from Jieba),
     * proper nouns (CC-CEDICT readings start with a capital), and the user's «Не учить».
     */
    fun studyable(word: String, dict: ZhDictionary): Boolean {
        val e = dict.lookup(word) ?: return false
        if (!e.hasDefinition) return false
        return !(e.pinyin.firstOrNull()?.isUpperCase() ?: false)
    }

    /** A proper name outside the HSK lists: in the names lexicon, or a CC-CEDICT reading with a capital. */
    fun isName(word: String, engine: ZhEngine): Boolean {
        if (engine.hsk.hsk3(word) > 0 || engine.hsk.hsk2(word) > 0) return false
        if (engine.nameKind(word) != null) return true
        return engine.dictionary.lookup(word)?.pinyin?.firstOrNull()?.isUpperCase() == true
    }

    fun candidate(word: String, count: Int, firstChapter: Int, engine: ZhEngine): StudyCandidate {
        val e = engine.dictionary.lookup(word)
        return StudyCandidate(word, count, e?.pinyin.orEmpty(), engine.cardTranslation(word).orEmpty(), firstChapter)
    }

    /** Share of the book's word occurrences known now (names and «Не учить» left out), percent. */
    fun knownPercent(words: List<BookWord>, tokens: Int, known: Set<String>, engine: ZhEngine): Int {
        val matcher = KnownMatcher(known, engine)
        val counted = words.filter { !it.ignored && !isName(it.word, engine) }
        val base = counted.sumOf { it.count }.coerceAtMost(tokens).coerceAtLeast(1)
        return percent(counted.sumOf { if (matcher.isKnown(it.word)) it.count else 0 }, base)
    }

    fun book(
        words: List<BookWord>,
        tokens: Int,
        known: Set<String>,
        saved: Set<String>,
        engine: ZhEngine,
        limit: Int = 50
    ): BookStats {
        val matcher = KnownMatcher(known, engine)
        // Names and «Не учить» words are not part of what can be learned: leave them out of the percentages.
        val counted = words.filter { !it.ignored && !isName(it.word, engine) }
        val base = counted.sumOf { it.count }.coerceAtMost(tokens).coerceAtLeast(1)
        var knownTokens = 0
        var unknownDistinct = 0
        for (w in counted) {
            if (matcher.isKnown(w.word)) knownTokens += w.count else unknownDistinct++
        }
        val levels = HskSystem.HSK3.levels.map { level ->
            val listed = engine.hsk.wordsUpTo(HskSystem.HSK3, level).toHashSet()
            val covered = KnownMatcher(listed + KnownExpansion.expand(listed, engine.dictionary), engine)
            LevelCoverage(level, percent(counted.sumOf { if (covered.isKnown(it.word)) it.count else 0 }, base))
        }
        val candidates = words.asSequence()
            .filter { !it.ignored && !matcher.isKnown(it.word) && it.word !in saved && studyable(it.word, engine.dictionary) && !isName(it.word, engine) }
            .sortedByDescending { it.count }
            .take(limit)
            .map { candidate(it.word, it.count, it.firstChapter, engine) }
            .toList()
        return BookStats(base, counted.size, percent(knownTokens, base), unknownDistinct, levels, candidates)
    }

    /** The most frequent unknown words of one chapter, with their count in that chapter. */
    fun chapter(
        chapter: ChapterContent,
        known: Set<String>,
        saved: Set<String>,
        ignored: Set<String>,
        engine: ZhEngine,
        limit: Int = 50
    ): List<StudyCandidate> {
        val matcher = KnownMatcher(known, engine)
        val counts = HashMap<String, Int>()
        for (b in chapter.blocks) {
            if (b !is ReaderBlock.Text) continue
            val inNames = nameTokens(b, ignored, engine)
            for ((i, t) in b.tokens.withIndex()) {
                if (t.kind == TokenKind.HAN && i !in inNames) counts[t.text] = (counts[t.text] ?: 0) + 1
            }
        }
        return counts.entries.asSequence()
            .filter { (w, _) -> !matcher.isKnown(w) && w !in saved && w !in ignored && studyable(w, engine.dictionary) && !isName(w, engine) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { (w, n) -> candidate(w, n, chapter.index, engine) }
            .toList()
    }

    /** Indexes of tokens that belong to a known name of the book ([names]: the book's ignored words). */
    fun nameTokens(block: ReaderBlock.Text, names: Set<String>, engine: ZhEngine): Set<Int> {
        val out = HashSet<Int>()
        for (r in NameRuns.runs(block.tokens, { it.text }, { it.kind == TokenKind.HAN }, engine.dictionary)) {
            if (r.joinToString("") { block.tokens[it].text } in names) out.addAll(r)
        }
        for (r in DotNames.runs(block.tokens, { it.text }, { it.kind == TokenKind.HAN }, engine.dictionary)) out.addAll(r)
        return out
    }

    /** The first sentence of the chapter that contains [word]. */
    fun firstSentence(chapter: ChapterContent, word: String): String? {
        for (b in chapter.blocks) {
            if (b !is ReaderBlock.Text) continue
            val t = b.tokens.firstOrNull { it.kind == TokenKind.HAN && it.text == word } ?: continue
            return ChapterBuilder.sentence(b.text, t.start, t.end)
        }
        return null
    }

    private fun percent(part: Int, total: Int) = if (total == 0) 0 else (part.toLong() * 100 / total).toInt()
}
