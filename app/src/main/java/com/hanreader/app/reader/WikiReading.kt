package com.hanreader.app.reader

import com.hanreader.app.*
import com.hanreader.app.epub.*
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.lang.PreparedText
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.wiki.*
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Display-only adapter: no synthetic Room book, reading history or source-book mutations. */
internal class WikiReading(val document: WikiDocument, val chapter: ChapterContent,
    val sourceBlocks: List<Int>, val layoutBlocks: List<Int>) : BookSource {
    override val title = document.article.title
    override val author = "Участники Википедии"
    override val chapters = listOf(EpubChapter(0, document.key, title))
    override val toc = document.blocks.mapIndexedNotNull { index, b -> if (b.heading > 0) TocEntry(b.text, 0, 0) else null }
    override fun blocks(chapterIndex: Int): List<EpubBlock> = emptyList()
    override fun bytes(path: String): ByteArray? = null
    override fun cover(): ByteArray? = null
    override fun close() = Unit
    fun book(block: Int, char: Int) = OpenBook(BookEntity(id = -document.article.id, title = title, author = author,
        fileName = document.key, chapterCount = 1, blockIndex = layoutBlocks[block.coerceIn(layoutBlocks.indices)], charOffset = char), this, chapter, false)
    fun source(layout: Int) = sourceBlocks[layout.coerceIn(sourceBlocks.indices)]

    companion object {
        suspend fun prepare(document: WikiDocument, engine: LanguageModule?, options: TextOptions): WikiReading {
            val prepare = engine?.chapter(document.blocks.map { it.text }, options) ?: ::plainWikiText
            val blocks = ArrayList<ReaderBlock>()
            val source = ArrayList<Int>()
            val layout = ArrayList<Int>()
            val images = document.images.withIndex().groupBy { it.value.beforeBlock }
            for (i in 0..document.blocks.size) {
                coroutineContext.ensureActive()
                images[i].orEmpty().forEach { indexed ->
                    blocks += ReaderBlock.Image(indexed.index.toString()); source += i.coerceAtMost(document.blocks.lastIndex)
                }
                if (i < document.blocks.size) {
                    layout += blocks.size
                    val b = document.blocks[i]; val p = prepare(b.text)
                    blocks += ReaderBlock.Text(p.text, b.heading > 0, p.tokens, p.shown)
                    source += i
                }
            }
            return WikiReading(document, ChapterContent(0, document.article.title, blocks, 0, document.blocks.sumOf { it.text.length }), source, layout)
        }
    }
}

internal data class WikiLearning(val unknown: Set<String> = emptySet(), val recommended: Set<String> = emptySet(),
    val glosses: Map<String, String> = emptyMap(), val knownHere: Set<String> = emptySet())

internal suspend fun wikiLearning(reading: WikiReading, engine: LanguageModule?, known: Set<String>, meanings: Map<String, String>,
    appearance: Appearance, studying: Set<String>, levelMark: LevelMark, pinyinMode: PinyinMode = PinyinMode.UNKNOWN): WikiLearning {
    if (engine == null) return WikiLearning()
    val matcher = KnownMatcher(known, engine)
    val wantRecommended = appearance.markRecommended || appearance.gloss == GlossMode.RECOMMENDED || pinyinMode == PinyinMode.RECOMMENDED
    val level = if (!wantRecommended) null else if (engine is ZhEngine) Recommendations.level(levelMark, matcher, engine.hsk)
        else engine.levels?.let { Recommendations.level(levelMark.level, matcher, it) }
    val recommended = if (level == null) emptySet() else Recommendations.forChapter(reading.chapter, matcher, studying, emptySet(), emptyMap(), engine,
        level, appearance.recommendedPerChapter).mapTo(HashSet()) { it.word }
    val unknown = HashSet<String>(); val knownHere = HashSet<String>(); val recommendedHere = HashSet<String>()
    val glosses = HashMap<String, String>()
    for ((layout, b) in reading.chapter.blocks.withIndex()) {
        coroutineContext.ensureActive()
        if (b !is ReaderBlock.Text || b.heading) continue
        val source = reading.source(layout)
        val noMarks = VocabularyStats.nameTokens(b, emptySet(), engine)
        b.tokens.forEachIndexed { index, t ->
            if (t.kind != TokenKind.HAN) return@forEachIndexed
            val key = ChapterSummary.keyOf(t, engine) ?: return@forEachIndexed
            val isKnown = key in known || matcher.isKnown(t.text)
            if (isKnown) knownHere += t.text
            if (!isKnown && index !in noMarks && key !in studying) unknown += t.text
            if (key in recommended) recommendedHere += t.text
            val show = when (appearance.gloss) { GlossMode.OFF -> false; GlossMode.UNKNOWN -> t.text in unknown; GlossMode.RECOMMENDED -> key in recommended }
            val own = meanings[ContextMeanings.keyOf(t, engine)]
            if (show || (own != null && appearance.gloss != GlossMode.OFF)) (own ?: engine.glossShort(t))?.takeIf { it.isNotBlank() }?.let {
                glosses["@$source:${t.start}"] = it
            }
        }
    }
    return WikiLearning(unknown, recommendedHere, glosses, knownHere)
}

internal fun plainWikiText(text: String): PreparedText {
    val tokens = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’−-][\\p{L}\\p{M}\\p{N}]+)*|\\s+|[^\\p{L}\\p{M}\\p{N}\\s]").findAll(text).map { match ->
        val word = match.value
        RToken(word, "", if (word.any(Char::isLetterOrDigit)) TokenKind.HAN else if (word.isBlank()) TokenKind.SPACE else TokenKind.PUNCT,
            match.range.first, match.range.last + 1, false)
    }.toList()
    return PreparedText(text, text, tokens)
}
