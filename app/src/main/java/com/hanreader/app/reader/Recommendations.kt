package com.hanreader.app.reader

import com.hanreader.app.BookWord
import com.hanreader.app.zh.TokenKind
import kotlin.math.ln

/** The reader's level on the HSK 3.0 scale (1..7, 7 = 7–9), and whether it was worked out automatically. */
data class ReaderLevel(val hsk3: Int, val auto: Boolean) {
    val title: String get() = "HSK ${HskSystem.HSK3.levelTitle(hsk3)}"
}

/** Why a word is recommended: shown in the word sheet. */
data class Recommendation(val word: String, val bookCount: Int, val chapterCount: Int, val level: Int, val score: Double)

object Recommendations {
    /**
     * The chosen HSK level if there is one (HSK 2.0 mapped onto 3.0 by vocabulary size), otherwise
     * the highest HSK 3.0 level whose words the reader knows for at least [AUTO_SHARE], minimum 1.
     */
    fun level(mark: LevelMark, known: KnownMatcher, hsk: HskLevels): ReaderLevel {
        if (mark.level > 0) {
            val l = if (mark.system == HskSystem.HSK3) mark.level else HSK2_TO_3[mark.level.coerceIn(1, 6)]
            return ReaderLevel(l, auto = false)
        }
        var level = 1
        for (l in HskSystem.HSK3.levels) {
            val words = hsk.wordsUpTo(HskSystem.HSK3, l).filter { hsk.hsk3(it) == l }
            if (words.isEmpty()) continue
            val share = words.count { known.isKnown(it) }.toDouble() / words.size
            if (share >= AUTO_SHARE) level = l else break
        }
        return ReaderLevel(level, auto = true)
    }

    /**
     * Up to [limit] words of the chapter worth learning now: unknown, not in the cards, not a name,
     * with a dictionary meaning; ranked by how often they occur in the book and the chapter, and by
     * how close their level is to one above the reader's ("i+1").
     */
    fun forChapter(
        chapter: ChapterContent,
        known: KnownMatcher,
        saved: Set<String>,
        names: Set<String>,
        bookWords: Map<String, BookWord>,
        engine: ZhEngine,
        level: ReaderLevel,
        limit: Int
    ): List<Recommendation> {
        val counts = HashMap<String, Int>()
        for (b in chapter.blocks) {
            if (b !is ReaderBlock.Text) continue
            val inNames = VocabularyStats.nameTokens(b, names, engine)
            for ((i, t) in b.tokens.withIndex()) {
                if (t.kind == TokenKind.HAN && i !in inNames) counts[t.text] = (counts[t.text] ?: 0) + 1
            }
        }
        return counts.entries.asSequence()
            .filter { (w, _) ->
                w !in saved && w !in names && !known.isKnown(w) &&
                    bookWords[w]?.ignored != true && VocabularyStats.studyable(w, engine.dictionary)
            }
            .map { (w, inChapter) ->
                val wl = wordLevel(w, engine)
                val inBook = bookWords[w]?.count ?: inChapter
                Recommendation(w, inBook, inChapter, wl, score(inBook, inChapter, wl, level.hsk3))
            }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.word })
            .take(limit)
            .toList()
    }

    /** HSK 3.0 level of a word; for words outside the lists, a level guessed from general frequency. */
    fun wordLevel(word: String, engine: ZhEngine): Int {
        engine.hsk.hsk3(word).takeIf { it > 0 }?.let { return it }
        val f = engine.dictionary.freq(word)
        return when {
            f >= 20_000 -> 2
            f >= 5_000 -> 4
            f >= 1_000 -> 6
            f >= 200 -> 7
            else -> 8
        }
    }

    /** i+1: a word one level up scores highest; gaps below the level still help; far above fades out. */
    fun closeness(wordLevel: Int, readerLevel: Int): Double = when (wordLevel - readerLevel) {
        in Int.MIN_VALUE..0 -> 0.9
        1 -> 1.0
        2 -> 0.6
        3 -> 0.25
        else -> 0.08
    }

    fun score(inBook: Int, inChapter: Int, wordLevel: Int, readerLevel: Int): Double {
        if (inChapter <= 0) return 0.0
        return ln(1.0 + inBook) * (1.0 + 0.35 * ln(1.0 + inChapter)) * closeness(wordLevel, readerLevel)
    }

    private const val AUTO_SHARE = 0.8

    /** HSK 2.0 level → HSK 3.0 level of about the same vocabulary size (300/600/1,200/2,500/5,000 words). */
    private val HSK2_TO_3 = intArrayOf(0, 1, 1, 1, 2, 3, 5)
}
