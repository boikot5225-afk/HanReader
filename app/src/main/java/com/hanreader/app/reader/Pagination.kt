package com.hanreader.app.reader

import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.yield

/** What a page shows: whole or partial paragraphs, images, the chapter's study banner. */
sealed interface PageItem {
    val block: Int

    /** Lines [lines] of paragraph [block]. */
    data class Lines(override val block: Int, val lines: IntRange, val spacingBefore: Boolean) : PageItem

    /** Image [block], drawn [heightPx] tall. */
    data class Image(override val block: Int, val heightPx: Float, val spacingBefore: Boolean) : PageItem

    /** «Знакомо N% · Подготовиться» at the top of the chapter. */
    data object Banner : PageItem {
        override val block: Int = -1
    }
}

/** [firstChar]: the character of paragraph [firstBlock] where the page starts. */
data class Page(val items: List<PageItem>, val firstBlock: Int, val chars: Int, val firstChar: Int = 0)

/** Everything that changes where lines break; pages are rebuilt when any of it changes. */
data class PageSpec(
    val widthPx: Float,
    val heightPx: Float,
    val paragraphSpacingPx: Float,
    val bannerHeightPx: Float,
    val showBanner: Boolean,
    val gapPx: Float,
    val lineGapPx: Float,
    val showPinyin: Boolean,
    val hidePinyinFor: Set<String>?,
    val justify: Boolean,
    val indent: Boolean,
    val glossFor: Map<String, String>? = null
)

object Paginator {
    /**
     * Splits a chapter into pages of [spec.heightPx]. Paragraphs break between lines; an image
     * never splits and is scaled to fit. Runs on the main thread (text measuring) and yields
     * between paragraphs so the UI stays responsive.
     */
    internal suspend fun paginate(
        chapter: ChapterContent,
        metrics: RubyMetrics,
        spec: PageSpec,
        imageAspect: suspend (String) -> Float?
    ): List<Page> {
        val pages = ArrayList<Page>()
        var items = ArrayList<PageItem>()
        var y = 0f
        var chars = 0
        var first = 0
        var firstChar = 0
        fun flush() {
            if (items.isNotEmpty()) pages += Page(items, first, chars, firstChar)
            items = ArrayList(); y = 0f; chars = 0; firstChar = 0
        }
        if (spec.showBanner) {
            items += PageItem.Banner
            y = spec.bannerHeightPx + spec.paragraphSpacingPx
        }
        for ((i, block) in chapter.blocks.withIndex()) {
            if (i % 12 == 0) yield()
            when (block) {
                is ReaderBlock.Image -> {
                    val aspect = imageAspect(block.path) ?: 0.66f
                    val h = minOf(spec.widthPx * aspect, spec.heightPx * 0.9f).coerceAtLeast(48f)
                    val gap = if (items.isEmpty()) 0f else spec.paragraphSpacingPx
                    if (y + gap + h > spec.heightPx && items.isNotEmpty()) flush()
                    if (items.isEmpty()) first = i
                    items += PageItem.Image(i, h, spacingBefore = items.isNotEmpty())
                    y += (if (items.size > 1) gap else 0f) + h
                }
                is ReaderBlock.Text -> {
                    val laid = layout(
                        block.tokens, spec.widthPx, metrics, block.heading, spec.showPinyin, spec.hidePinyinFor,
                        spec.gapPx, spec.lineGapPx, spec.justify, spec.indent, spec.glossFor, style = block.style
                    )
                    if (laid.lineCount == 0) continue
                    val lineChars = IntArray(laid.lineCount)
                    val lineStart = IntArray(laid.lineCount) { Int.MAX_VALUE }
                    for (p in laid.items) {
                        val t = block.tokens[p.token]
                        val l = laid.lineOf(p).coerceIn(0, laid.lineCount - 1)
                        if (t.kind == TokenKind.HAN) lineChars[l] += t.text.length
                        if (t.start < lineStart[l]) lineStart[l] = t.start
                    }
                    var line = 0
                    while (line < laid.lineCount) {
                        val gap = if (items.isEmpty() || line > 0 || block.style.compactBefore) 0f else spec.paragraphSpacingPx
                        var room = ((spec.heightPx - y - gap) / laid.lineHeight).toInt()
                        if (room <= 0) {
                            if (items.isNotEmpty()) {
                                flush()
                                continue
                            }
                            // A line taller than the whole page (huge font in a small window): it gets a page
                            // of its own, cut at the bottom. Flushing an empty page again would never end.
                            room = 1
                        }
                        val take = minOf(room, laid.lineCount - line)
                        if (items.isEmpty()) { first = i; firstChar = if (line == 0) 0 else lineStart[line].takeIf { it != Int.MAX_VALUE } ?: 0 }
                        items += PageItem.Lines(i, line until line + take, spacingBefore = gap > 0f)
                        y += gap + take * laid.lineHeight
                        for (k in line until line + take) chars += lineChars[k]
                        line += take
                        if (line < laid.lineCount) flush()
                    }
                }
            }
        }
        flush()
        if (pages.isEmpty()) pages += Page(listOf(PageItem.Banner).takeIf { spec.showBanner } ?: emptyList(), 0, 0)
        return pages
    }

    /**
     * The page to open for a saved position: the page holding character [char] of paragraph
     * [block], i.e. the last page that starts at or before it. Works with any line breaking, so a
     * change of font or hints between sessions still opens the same text.
     */
    fun pageFor(pages: List<Page>, block: Int, char: Int = 0): Int {
        var best = 0
        for ((k, p) in pages.withIndex()) {
            if (p.items.any { it !is PageItem.Banner }) {
                val before = p.firstBlock < block || (p.firstBlock == block && p.firstChar <= char)
                if (before) best = k else break
            }
        }
        return best
    }
}
