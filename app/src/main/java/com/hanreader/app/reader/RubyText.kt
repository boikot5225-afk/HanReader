package com.hanreader.app.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.hanreader.app.ToneColors
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.hanreader.app.Appearance
import com.hanreader.app.TextAlignMode
import com.hanreader.app.ThemeSpec
import com.hanreader.app.zh.TokenKind

/**
 * Measures and caches every distinct string of a chapter once, for the two styles
 * (characters and pinyin). Shared by all paragraphs on screen.
 */
@Stable
class RubyMetrics(
    private val measurer: TextMeasurer,
    val hanStyle: TextStyle,
    val pinyinStyle: TextStyle,
    val headingStyle: TextStyle,
    /** The small translation above the pinyin. */
    val glossStyle: TextStyle = pinyinStyle,
    val tones: ToneColors = ToneColors.OFF,
    /** Colours of tones 1-4 and the neutral tone. */
    val tonePalette: List<Color> = Tones.LIGHT
) {
    private val gloss = HashMap<String, TextLayoutResult>()

    /** A translation at most [maxWidthPx] wide, cut with an ellipsis. */
    fun gloss(text: String, maxWidthPx: Int): TextLayoutResult {
        val w = (maxWidthPx / 4 * 4).coerceAtLeast(8)
        return gloss.getOrPut("$text|$w") {
            measurer.measure(
                text, glossStyle, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
                constraints = Constraints(maxWidth = w)
            )
        }
    }

    val glossHeight: Int by lazy { measurer.measure("Яр", glossStyle, maxLines = 1).size.height }

    private val han = HashMap<String, TextLayoutResult>()
    private val heading = HashMap<String, TextLayoutResult>()
    private val pinyin = HashMap<String, TextLayoutResult>()

    fun han(text: String, isHeading: Boolean): TextLayoutResult =
        if (isHeading) heading.getOrPut(text) { measurer.measure(text, headingStyle, softWrap = false, maxLines = 1) }
        else han.getOrPut(text) { measurer.measure(text, hanStyle, softWrap = false, maxLines = 1) }

    fun pinyin(text: String): TextLayoutResult =
        pinyin.getOrPut(text) { measurer.measure(text, pinyinStyle, softWrap = false, maxLines = 1) }

    private val tonedHan = HashMap<String, TextLayoutResult>()
    private val tonedPinyin = HashMap<String, TextLayoutResult>()

    /** The characters of a token as drawn: in the chosen script, coloured by tone if asked. */
    fun hanOf(t: RToken, isHeading: Boolean): TextLayoutResult {
        if (!tones.han || t.syllables.size != t.display.length) return han(t.display, isHeading)
        val key = (if (isHeading) "h|" else "b|") + t.display + "|" + t.pinyin
        return tonedHan.getOrPut(key) {
            val text = buildAnnotatedString {
                t.display.forEachIndexed { k, c -> withStyle(SpanStyle(color = tonePalette[Tones.of(t.syllables[k]) - 1])) { append(c) } }
            }
            measurer.measure(text, if (isHeading) headingStyle else hanStyle, softWrap = false, maxLines = 1)
        }
    }

    /** The reading of a token, each syllable in its tone's colour if asked. */
    fun pinyinOf(t: RToken): TextLayoutResult {
        if (!tones.pinyin || t.syllables.isEmpty() || t.syllables.sumOf { it.length } != t.pinyin.length) return pinyin(t.pinyin)
        return tonedPinyin.getOrPut(t.pinyin + "|" + t.syllables.joinToString(" ")) {
            val text = buildAnnotatedString {
                for (syl in t.syllables) withStyle(SpanStyle(color = tonePalette[Tones.of(syl) - 1])) { append(syl) }
            }
            measurer.measure(text, pinyinStyle, softWrap = false, maxLines = 1)
        }
    }

    /** True if the token's layouts carry their own colours (drawn without a colour override). */
    fun tonedHan(t: RToken) = tones.han && t.syllables.size == t.display.length && t.syllables.isNotEmpty()
    fun tonedPinyin(t: RToken) = tones.pinyin && t.syllables.isNotEmpty() && t.syllables.sumOf { it.length } == t.pinyin.length

    val emWidth: Int by lazy { han("汉", false).size.width }
    val pinyinHeight: Int by lazy { pinyin("hàn").size.height }
}

/** Spreads the free space of each full line evenly between its tokens. */
/**
 * Stretches full lines to the margin. Chinese (no spaces): the free room is shared by every gap
 * between tokens. A text with spaces ([spaceBefore] true for at least half the gaps): only the spaces grow,
 * so a comma or a full stop stays next to its word.
 */
internal fun justifyLines(items: MutableList<Placed>, lines: List<IntRange>, widthPx: Float, spaceBefore: (Placed) -> Boolean = { false }) {
    for (range in lines) {
        val first = items[range.first]
        val last = items[range.last]
        val used = last.x + last.width - first.x
        val free = widthPx - first.x - used
        if (free <= 0f) continue
        // Space mode only for a text written with spaces: a Chinese line with an English name keeps the old way.
        val spaces = (range.first + 1..range.last).count { spaceBefore(items[it]) }.takeIf { it * 2 >= range.last - range.first } ?: 0
        val gaps = if (spaces > 0) spaces else range.last - range.first
        val step = free / gaps
        // Do not stretch absurdly when a long token pushed the line break early.
        val limit = if (spaces > 0) first.hanLayout.size.height * 0.9f else first.hanLayout.size.width * 0.6f
        if (step > limit) continue
        var shift = 0f
        for (i in range) {
            val p = items[i]
            if (i > range.first && (spaces == 0 || spaceBefore(p))) shift += step
            items[i] = Placed(p.token, p.x + shift, p.lineTop, p.width, p.hanLayout, p.pinyinLayout)
        }
    }
}

/** One token placed in the paragraph. */
internal class Placed(
    val token: Int,
    val x: Float,
    val lineTop: Float,
    val width: Float,
    val hanLayout: TextLayoutResult,
    val pinyinLayout: TextLayoutResult?
)

/** A laid-out paragraph: every line has the same height [lineHeight]; line k starts at k * lineHeight. */
/** A translation drawn above the pinyin of token [token]. */
internal class GlossPlaced(val token: Int, val x: Float, val lineTop: Float, val layout: TextLayoutResult)

internal class RubyLayout(
    val items: List<Placed>,
    val height: Float,
    val hanTop: Float,
    val hanHeight: Float,
    val lineHeight: Float,
    val lineCount: Int,
    val pinTop: Float = 0f,
    val glosses: List<GlossPlaced> = emptyList()
) {
    fun lineOf(p: Placed): Int = if (lineHeight <= 0f) 0 else (p.lineTop / lineHeight + 0.5f).toInt()

    /** The token under [pos] or nearest to it on that line (lines limited to [shown]). */
    fun tokenNear(pos: Offset, shown: IntRange): Int? {
        if (items.isEmpty() || lineHeight <= 0f) return null
        val line = (pos.y / lineHeight).toInt().coerceIn(shown.first, shown.last)
        val onLine = items.filter { lineOf(it) == line }.ifEmpty { return null }
        return (onLine.firstOrNull { pos.x >= it.x && pos.x < it.x + it.width }
            ?: if (pos.x < onLine.first().x) onLine.first() else onLine.last()).token
    }
}

private const val CLOSING = "，。、；：？！”’）》」』〉】…—·,.;:?!)"

internal fun layout(
    tokens: List<RToken>,
    widthPx: Float,
    metrics: RubyMetrics,
    heading: Boolean,
    showPinyin: Boolean,
    hidePinyinFor: Set<String>?,
    gapPx: Float,
    lineGapPx: Float,
    justify: Boolean,
    indent: Boolean,
    /** Short translations to draw above the pinyin (word -> text); null: no translation row. */
    glossFor: Map<String, String>? = null,
    /** Only these tokens get their translation drawn (it fades after a few appearances); null: all. */
    glossOnly: Set<Int>? = null,
    style: com.hanreader.app.epub.EpubTextStyle = com.hanreader.app.epub.EpubTextStyle(),
    /** The paragraph's index in its chapter: the reader's meaning for one place is under «@<block>:<start>». */
    blockIndex: Int = -1
): RubyLayout {
    if (style != com.hanreader.app.epub.EpubTextStyle()) {
        val inset = if (style.inset) minOf(metrics.emWidth * 1.4f, widthPx * 0.12f) else 0f
        val contentWidth = (widthPx - inset * 2).coerceAtLeast(1f)
        val aligned = style.alignment in setOf("left", "center", "right")
        val laid = layout(tokens, contentWidth, metrics, heading, showPinyin, hidePinyinFor,
            gapPx, lineGapPx, justify && !aligned && !(style.inset && style.indent == false),
            style.indent ?: (indent && !style.inset), glossFor, glossOnly, blockIndex = blockIndex)
        val shifts = laid.items.groupBy { it.lineTop }.mapValues { (_, line) ->
            val end = line.maxOf { it.x + it.width }
            inset + when (style.alignment) {
                "right" -> (contentWidth - end).coerceAtLeast(0f)
                "center" -> (contentWidth - end).coerceAtLeast(0f) / 2f
                else -> 0f
            }
        }
        val placed = laid.items.map { p ->
            Placed(p.token, p.x + shifts.getValue(p.lineTop), p.lineTop, p.width, p.hanLayout, p.pinyinLayout)
        }
        // Refit hints after alignment: a long hint over a short, right-aligned line must stay on screen.
        val glosses = if (glossFor != null && !heading) placeGlosses(placed, tokens, glossFor, metrics, widthPx, glossOnly, blockIndex) else emptyList()
        return RubyLayout(placed, laid.height, laid.hanTop, laid.hanHeight, laid.lineHeight, laid.lineCount, laid.pinTop, glosses)
    }
    val hyphenate = com.hanreader.app.lang.Languages.current.hyphenate
    // The pinyin row is kept even above known words, so lines keep one rhythm; so is the translation row.
    val glossH = if (glossFor != null && !heading) metrics.glossHeight.toFloat() else 0f
    val pinH = if (showPinyin) metrics.pinyinHeight.toFloat() else 0f
    val hanH = metrics.han("汉", heading).size.height.toFloat()
    val lineH = glossH + pinH + hanH + lineGapPx
    val items = ArrayList<Placed>(tokens.size)
    var x = if (heading || !indent) 0f else metrics.emWidth * 2f
    // Index in items where the current line starts, and which lines may be stretched.
    var lineStart = 0
    val stretch = ArrayList<IntRange>()
    var y = 0f
    var lineHasContent = false
    for ((i, t) in tokens.withIndex()) {
        if (t.kind == TokenKind.SPACE) {
            if (t.text.contains('\n')) {
                x = 0f; y += lineH; lineHasContent = false
                lineStart = items.size
            } else if (lineHasContent) {
                x += metrics.emWidth * 0.3f
            }
            continue
        }
        val hanLayout = metrics.hanOf(t, heading)
        val wantPinyin = showPinyin && t.pinyin.isNotEmpty() && (hidePinyinFor == null || t.text !in hidePinyinFor)
        val pinLayout = if (wantPinyin) metrics.pinyinOf(t) else null
        val gap = if (showPinyin && t.kind == TokenKind.HAN) gapPx else 0f
        val w = maxOf(hanLayout.size.width.toFloat(), pinLayout?.size?.width?.toFloat() ?: 0f) + gap
        // Closing punctuation never starts a line: let it hang past the margin.
        val hangs = t.kind == TokenKind.PUNCT && t.text[0] in CLOSING
        if (lineHasContent && x + w > widthPx && !hangs) {
            // A language with hyphenation: the part of the word that fits stays, with a hyphen.
            val split = if (hyphenate != null && t.kind == TokenKind.HAN && !heading && pinLayout == null && t.display == t.text)
                hyphenate(t.display).lastOrNull { p -> x + metrics.han(t.display.substring(0, p) + "-", false).size.width <= widthPx }
            else null
            if (split != null) {
                val head = metrics.han(t.display.substring(0, split) + "-", false)
                items += Placed(i, x, y, head.size.width.toFloat(), head, null)
                if (items.size - lineStart >= 2) stretch += lineStart until items.size
                x = 0f; y += lineH
                lineStart = items.size
                val tail = metrics.han(t.display.substring(split), false)
                items += Placed(i, x, y, tail.size.width.toFloat(), tail, null)
                x += tail.size.width
                lineHasContent = true
                continue
            }
            // A full line ends here: it may be justified (the last line of the paragraph is not).
            if (items.size - lineStart >= 2) stretch += lineStart until items.size
            x = 0f; y += lineH
            lineStart = items.size
        }
        items += Placed(i, x, y, w, hanLayout, pinLayout)
        x += w
        lineHasContent = true
    }
    if (justify && !heading) justifyLines(items, stretch, widthPx) { p -> p.token > 0 && tokens[p.token - 1].kind == TokenKind.SPACE }
    val count = if (items.isEmpty()) 0 else (y / lineH + 0.5f).toInt() + 1
    val glosses = if (glossH > 0f) placeGlosses(items, tokens, glossFor!!, metrics, widthPx, glossOnly, blockIndex) else emptyList()
    return RubyLayout(items, if (items.isEmpty()) 0f else y + lineH, glossH + pinH, hanH, lineH, count, glossH, glosses)
}

/**
 * Each translation is centred over its word and may spread over neighbours that have none: on a
 * line, the space is split half-way between the centres of translated words; what does not fit is
 * cut with an ellipsis. Word widths never change, so lines break exactly as without translations.
 */
/** The translation of a token: "text|reading" first (a character read as on the page), then the word. */
internal fun glossOf(glossFor: Map<String, String>, t: RToken, block: Int = -1): String? {
    // The reader's meaning for this very place (ContextMeanings) wins.
    if (block >= 0) glossFor["@$block:${t.start}"]?.let { return it }
    // An empty hint for a reading: no hint at all (a word inside a phrase whose first word carries it).
    if (t.pinyin.isNotEmpty()) glossFor[t.text + "|" + t.pinyin]?.let { return it.ifEmpty { null } }
    return glossFor[t.text]
}

private fun placeGlosses(
    items: List<Placed>,
    tokens: List<RToken>,
    glossFor: Map<String, String>,
    metrics: RubyMetrics,
    widthPx: Float,
    only: Set<Int>? = null,
    block: Int = -1
): List<GlossPlaced> {
    val out = ArrayList<GlossPlaced>()
    // A word broken across lines gets its translation once, over its first part.
    val byLine = items.distinctBy { it.token }.filter { tokens[it.token].kind == TokenKind.HAN && (only == null || it.token in only || block >= 0 && "@$block:${tokens[it.token].start}" in glossFor) &&
        glossOf(glossFor, tokens[it.token], block) != null }
        .groupBy { it.lineTop }
    val pad = metrics.emWidth * 0.15f
    for ((top, line) in byLine) {
        val centres = line.map { it.x + it.width / 2f }
        for ((k, p) in line.withIndex()) {
            val left = if (k == 0) 0f else (centres[k - 1] + centres[k]) / 2f + pad
            val right = if (k == line.lastIndex) widthPx else (centres[k] + centres[k + 1]) / 2f - pad
            val room = (right - left).coerceAtLeast(metrics.emWidth.toFloat())
            val g = metrics.gloss(glossOf(glossFor, tokens[p.token], block)!!, room.toInt())
            val gx = (centres[k] - g.size.width / 2f).coerceIn(left, (right - g.size.width).coerceAtLeast(left))
            out += GlossPlaced(p.token, gx, top, g)
        }
    }
    return out
}

/**
 * A paragraph of Chinese with optional pinyin above each word, drawn on a Canvas.
 * No per-word composables: layout is computed once per width/style, taps are hit-tested.
 */
@Composable
fun RubyParagraph(
    block: ReaderBlock.Text,
    metrics: RubyMetrics,
    showPinyin: Boolean,
    /** Words that get no pinyin ("unknown only" mode); null shows pinyin over every word. */
    hidePinyinFor: Set<String>?,
    saved: Set<String>,
    selection: IntRange?,
    colors: RubyColors,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Only these lines (page mode: a paragraph split across pages); null draws the whole paragraph. */
    lines: IntRange? = null,
    /** Tokens to mark as a search match. */
    highlight: IntRange? = null,
    /** Words to underline in red (not known yet) and in blue (recommended); null: no marks. */
    unknownMarks: Set<String>? = null,
    recommendedMarks: Set<String>? = null,
    /** Token indices never marked (characters of a name). */
    noMarks: Set<Int> = emptySet(),
    /** Short translations above the pinyin (word -> text); null: no translation row. */
    glossFor: Map<String, String>? = null,
    /** The reader's highlights: token index -> colour. */
    marked: Map<Int, Color> = emptyMap(),
    /** Tokens that end a highlight with a note (a small dot is drawn after them). */
    noted: Set<Int> = emptySet(),
    /**
     * Long press and drag selects text: (anchor token, token under the finger, finger lifted).
     * Null: long press does nothing.
     */
    onRange: ((Int, Int, Boolean) -> Unit)? = null,
    /** Tokens being read aloud right now: underlined boldly. */
    spoken: IntRange? = null,
    /** Tokens whose translation is drawn; null: every one in [glossFor]. */
    glossOnly: Set<Int>? = null,
    /** A tap on a footnote mark (the «4» of «Villa4»): shows the note's text. */
    onNote: ((String) -> Unit)? = null,
    /** The paragraph's index in its chapter, for the reader's meanings of one place; -1: none. */
    blockIndex: Int = -1
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val gapPx = with(density) { colors.wordGap.toPx() }
        val lineGapPx = with(density) { colors.lineGap.toPx() }
        // Names (noMarks) get no translation over them either.
        val only = if (noMarks.isEmpty()) glossOnly else (glossOnly ?: block.tokens.indices.toSet()) - noMarks
        val laid = remember(block, widthPx, metrics, showPinyin, hidePinyinFor, colors, glossFor, only, blockIndex) {
            layout(block.tokens, widthPx, metrics, block.heading, showPinyin, hidePinyinFor, gapPx, lineGapPx, colors.justify, colors.indent, glossFor, only, block.style, blockIndex)
        }
        val shown = lines ?: (0 until laid.lineCount)
        val offsetY = shown.first * laid.lineHeight
        val heightPx = if (lines == null) laid.height else (shown.last - shown.first + 1) * laid.lineHeight
        val heightDp = with(density) { heightPx.toDp() }
        val haptics = LocalHapticFeedback.current
        Canvas(
            Modifier.fillMaxWidth().height(heightDp).pointerInput(laid, shown) {
                // Consume only taps on a word: other taps reach the page (turn, menu).
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pos = down.position + Offset(0f, offsetY)
                    val under = laid.items.firstOrNull {
                        laid.lineOf(it) in shown &&
                            pos.x >= it.x && pos.x < it.x + it.width &&
                            pos.y >= it.lineTop && pos.y < it.lineTop + laid.hanTop + laid.hanHeight + lineGapPx
                    }
                    val note = under?.let { p -> noteOf(block, p.token) }?.takeIf { onNote != null }
                    val hit = under?.takeIf { block.tokens[it.token].kind == TokenKind.HAN || note != null }
                    if (hit == null && onRange == null) return@awaitEachGesture
                    // A tap, a swipe (left to the page) or, held still, a long press that starts a selection.
                    var tapped = false
                    val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            if (!c.pressed) {
                                // Only a tap on a word is ours; any other tap turns the page or opens the menu.
                                if (hit != null && (c.position - down.position).getDistance() < viewConfiguration.touchSlop) { tapped = true; c.consume() }
                                return@withTimeoutOrNull false
                            }
                            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    } == null
                    if (tapped && note != null) { onNote?.invoke(note.text); return@awaitEachGesture }
                    if (tapped && hit != null) { onTap(hit.token); return@awaitEachGesture }
                    if (!held || onRange == null) return@awaitEachGesture
                    val anchor = laid.tokenNear(pos, shown) ?: return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    var to = anchor
                    onRange(anchor, to, false)
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                        if (!c.pressed) break
                        val t = laid.tokenNear(c.position + Offset(0f, offsetY), shown) ?: continue
                        if (t != to) { to = t; onRange(anchor, to, false) }
                    }
                    onRange(anchor, to, true)
                }
            }
        ) {
            val pad = 2f
            translate(top = -offsetY) {
            for (g in laid.glosses) {
                if (lines != null && ((g.lineTop / laid.lineHeight + 0.5f).toInt()) !in shown) continue
                drawText(g.layout, color = colors.gloss, topLeft = Offset(g.x, g.lineTop))
            }
            for (p in laid.items) {
                if (lines != null && laid.lineOf(p) !in shown) continue
                val t = block.tokens[p.token]
                val selected = selection != null && p.token in selection
                if (highlight != null && p.token in highlight && !selected) {
                    drawRoundRect(
                        colors.saved.copy(alpha = 0.28f),
                        topLeft = Offset(p.x - pad, p.lineTop),
                        size = Size(p.width + pad, laid.hanTop + laid.hanHeight),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }
                marked[p.token]?.let { c ->
                    if (!selected) drawRect(
                        c.copy(alpha = 0.32f),
                        topLeft = Offset(p.x, p.lineTop + laid.hanTop - 2f),
                        size = Size(p.width, laid.hanHeight + 4f)
                    )
                }
                val isSaved = t.kind == TokenKind.HAN && t.text in saved
                if (selected) {
                    drawRoundRect(
                        colors.selection,
                        topLeft = Offset(p.x - pad, p.lineTop),
                        size = Size(p.width + pad, laid.hanTop + laid.hanHeight),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                }
                val hanW = p.hanLayout.size.width
                val inner = p.width - (if (showPinyin && t.kind == TokenKind.HAN) gapPx else 0f)
                p.pinyinLayout?.let { pl ->
                    drawText(
                        pl,
                        color = if (metrics.tonedPinyin(t)) Color.Unspecified else if (isSaved) colors.saved else colors.pinyin,
                        topLeft = Offset(p.x + (inner - pl.size.width) / 2f, p.lineTop + laid.pinTop)
                    )
                }
                drawText(
                    p.hanLayout,
                    // A footnote mark in the accent colour: tap it to read the note.
                    color = if (noteOf(block, p.token) != null) colors.saved else if (metrics.tonedHan(t)) Color.Unspecified else colors.text,
                    topLeft = Offset(p.x + (inner - hanW) / 2f, p.lineTop + laid.hanTop)
                )
                if (spoken != null && p.token in spoken) {
                    val yLine = p.lineTop + laid.hanTop + laid.hanHeight + 1f
                    drawLine(colors.saved, Offset(p.x, yLine), Offset(p.x + p.width - gapPx, yLine), strokeWidth = 6f)
                }
                if (p.token in noted) {
                    val c = marked[p.token] ?: colors.saved
                    drawCircle(c, radius = 5f, center = Offset(p.x + p.width - 1f, p.lineTop + laid.hanTop + 3f))
                }
                val lineColor = when {
                    t.kind != TokenKind.HAN -> null
                    isSaved -> colors.saved
                    p.token in noMarks -> null
                    recommendedMarks != null && t.text in recommendedMarks -> colors.recommendedLine
                    unknownMarks != null && t.text in unknownMarks -> colors.unknownLine
                    else -> null
                }
                if (lineColor != null) {
                    val yLine = p.lineTop + laid.hanTop + laid.hanHeight - 2f
                    drawLine(
                        lineColor,
                        Offset(p.x + (inner - hanW) / 2f, yLine),
                        Offset(p.x + (inner + hanW) / 2f, yLine),
                        strokeWidth = 3f
                    )
                }
            }
            }
        }
    }
}

/** The footnote whose mark holds token [token] of [block], or null. */
internal fun noteOf(block: ReaderBlock.Text, token: Int): ReaderNote? {
    if (block.notes.isEmpty()) return null
    val t = block.tokens.getOrNull(token) ?: return null
    return block.notes.firstOrNull { t.start >= it.start && t.end <= it.end }
}

data class RubyColors(
    val text: Color,
    val pinyin: Color,
    val saved: Color,
    val selection: Color,
    val wordGap: androidx.compose.ui.unit.Dp,
    val lineGap: androidx.compose.ui.unit.Dp,
    val justify: Boolean = false,
    val indent: Boolean = true,
    val unknownLine: Color = Color(0xFFC62828),
    val recommendedLine: Color = Color(0xFF1E5BB8),
    val gloss: Color = Color(0xFF6B6459)
)

/** Text styles of the reader from the appearance settings and the theme. */
@Composable
fun rememberRubyMetrics(measurer: TextMeasurer, a: Appearance, spec: ThemeSpec, han: FontFamily): RubyMetrics =
    remember(measurer, a.fontSize, a.pinyinScale, spec, han, a.tones) {
        val size = a.fontSize.sp
        val base = TextStyle(fontSize = size, fontFamily = han, color = spec.text)
        RubyMetrics(
            measurer,
            hanStyle = base,
            pinyinStyle = TextStyle(
                fontSize = size * a.pinyinScale * spec.pinyinBoost,
                fontFamily = spec.pinyinFont,
                fontStyle = if (spec.pinyinItalic) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (spec.pinyinItalic) FontWeight.Medium else FontWeight.Normal,
                color = spec.pinyin
            ),
            headingStyle = base.copy(fontSize = size * 1.25f, fontWeight = FontWeight.SemiBold),
            glossStyle = TextStyle(fontSize = size * 0.42f, fontFamily = spec.body, color = spec.muted),
            tones = a.tones,
            tonePalette = if (spec.dark) Tones.DARK else Tones.LIGHT
        )
    }

fun rubyColors(a: Appearance, spec: ThemeSpec) = RubyColors(
    text = spec.text,
    pinyin = spec.pinyin,
    saved = spec.saved,
    selection = spec.selection,
    wordGap = 3.dp,
    lineGap = (a.fontSize * a.lineSpacing).dp,
    justify = a.align == TextAlignMode.JUSTIFY,
    indent = a.indent,
    unknownLine = spec.unknownLine,
    recommendedLine = spec.recommendedLine,
    gloss = spec.muted
)

/** Tone of a pinyin syllable from its mark (ā á ǎ à); 5 = neutral. */
object Tones {
    private const val MARKS = "āēīōūǖĀĒĪŌŪǕáéíóúǘÁÉÍÓÚǗǎěǐǒǔǚǍĚǏǑǓǙàèìòùǜÀÈÌÒÙǛ"

    fun of(syllable: String): Int {
        for (c in syllable) {
            val i = MARKS.indexOf(c)
            if (i >= 0) return i / 12 + 1
            if (c in '1'..'5') return c - '0'
        }
        return 5
    }

    val LIGHT = listOf(Color(0xFFD32F2F), Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFF7B1FA2), Color(0xFF8A8A8A))
    val DARK = listOf(Color(0xFFFF6B6B), Color(0xFF66D17A), Color(0xFF64B0FF), Color(0xFFCB8BFF), Color(0xFF9A9A9A))
}

/**
 * Fading translations: for each paragraph, the tokens whose translation is still drawn — a word's
 * first [repeats] appearances in the chapter, in reading order; later ones keep only the underline.
 */
/**
 * Tokens that keep their translation: a word's first [repeats] appearances counted from block
 * [fromBlock], where reading started in this chapter (a book opened in the middle of a chapter
 * shows the translations from there, not after appearances the reader never saw). Blocks before
 * it keep all their translations.
 */
internal fun fadingGlosses(chapter: ChapterContent, glossFor: Map<String, String>, repeats: Int, fromBlock: Int = 0): Map<Int, Set<Int>> {
    val out = HashMap<Int, Set<Int>>()
    val seen = HashMap<String, Int>()
    chapter.blocks.forEachIndexed { bi, b ->
        val keep = HashSet<Int>()
        (b as? ReaderBlock.Text)?.tokens?.forEachIndexed { ti, t ->
            // The reader's meaning for this place is always drawn.
            if (t.kind == TokenKind.HAN && "@$bi:${t.start}" in glossFor) keep += ti
            else if (t.kind == TokenKind.HAN && glossOf(glossFor, t, bi) != null && (bi < fromBlock || seen.merge(if (' ' in t.pinyin) t.pinyin else t.text, 1, Int::plus)!! <= repeats)) keep += ti
        }
        out[bi] = keep
    }
    return out
}
