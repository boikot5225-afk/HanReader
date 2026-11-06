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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
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
    val glossStyle: TextStyle = pinyinStyle
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

    val emWidth: Int by lazy { han("汉", false).size.width }
    val pinyinHeight: Int by lazy { pinyin("hàn").size.height }
}

/** Spreads the free space of each full line evenly between its tokens. */
internal fun justifyLines(items: MutableList<Placed>, lines: List<IntRange>, widthPx: Float) {
    for (range in lines) {
        val first = items[range.first]
        val last = items[range.last]
        val used = last.x + last.width - first.x
        val free = widthPx - first.x - used
        if (free <= 0f) continue
        val step = free / (range.last - range.first)
        // Do not stretch absurdly when a long token pushed the line break early.
        if (step > first.hanLayout.size.width * 0.6f) continue
        for ((k, i) in range.withIndex()) {
            val p = items[i]
            items[i] = Placed(p.token, p.x + step * k, p.lineTop, p.width, p.hanLayout, p.pinyinLayout)
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
    glossFor: Map<String, String>? = null
): RubyLayout {
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
        val hanLayout = metrics.han(t.text, heading)
        val wantPinyin = showPinyin && t.pinyin.isNotEmpty() && (hidePinyinFor == null || t.text !in hidePinyinFor)
        val pinLayout = if (wantPinyin) metrics.pinyin(t.pinyin) else null
        val gap = if (showPinyin && t.kind == TokenKind.HAN) gapPx else 0f
        val w = maxOf(hanLayout.size.width.toFloat(), pinLayout?.size?.width?.toFloat() ?: 0f) + gap
        // Closing punctuation never starts a line: let it hang past the margin.
        val hangs = t.kind == TokenKind.PUNCT && t.text[0] in CLOSING
        if (lineHasContent && x + w > widthPx && !hangs) {
            // A full line ends here: it may be justified (the last line of the paragraph is not).
            if (items.size - lineStart >= 2) stretch += lineStart until items.size
            x = 0f; y += lineH
            lineStart = items.size
        }
        items += Placed(i, x, y, w, hanLayout, pinLayout)
        x += w
        lineHasContent = true
    }
    if (justify && !heading) justifyLines(items, stretch, widthPx)
    val count = if (items.isEmpty()) 0 else (y / lineH + 0.5f).toInt() + 1
    val glosses = if (glossH > 0f) placeGlosses(items, tokens, glossFor!!, metrics, widthPx) else emptyList()
    return RubyLayout(items, if (items.isEmpty()) 0f else y + lineH, glossH + pinH, hanH, lineH, count, glossH, glosses)
}

/**
 * Each translation is centred over its word and may spread over neighbours that have none: on a
 * line, the space is split half-way between the centres of translated words; what does not fit is
 * cut with an ellipsis. Word widths never change, so lines break exactly as without translations.
 */
private fun placeGlosses(
    items: List<Placed>,
    tokens: List<RToken>,
    glossFor: Map<String, String>,
    metrics: RubyMetrics,
    widthPx: Float
): List<GlossPlaced> {
    val out = ArrayList<GlossPlaced>()
    val byLine = items.filter { tokens[it.token].kind == TokenKind.HAN && glossFor.containsKey(tokens[it.token].text) }
        .groupBy { it.lineTop }
    val pad = metrics.emWidth * 0.15f
    for ((top, line) in byLine) {
        val centres = line.map { it.x + it.width / 2f }
        for ((k, p) in line.withIndex()) {
            val left = if (k == 0) 0f else (centres[k - 1] + centres[k]) / 2f + pad
            val right = if (k == line.lastIndex) widthPx else (centres[k] + centres[k + 1]) / 2f - pad
            val room = (right - left).coerceAtLeast(metrics.emWidth.toFloat())
            val g = metrics.gloss(glossFor.getValue(tokens[p.token].text), room.toInt())
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
    glossFor: Map<String, String>? = null
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val gapPx = with(density) { colors.wordGap.toPx() }
        val lineGapPx = with(density) { colors.lineGap.toPx() }
        val laid = remember(block, widthPx, metrics, showPinyin, hidePinyinFor, colors, glossFor) {
            layout(block.tokens, widthPx, metrics, block.heading, showPinyin, hidePinyinFor, gapPx, lineGapPx, colors.justify, colors.indent, glossFor)
        }
        val shown = lines ?: (0 until laid.lineCount)
        val offsetY = shown.first * laid.lineHeight
        val heightPx = if (lines == null) laid.height else (shown.last - shown.first + 1) * laid.lineHeight
        val heightDp = with(density) { heightPx.toDp() }
        Canvas(
            Modifier.fillMaxWidth().height(heightDp).pointerInput(laid, shown) {
                // Consume only taps on a word: other taps reach the page (turn, menu).
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pos = down.position + Offset(0f, offsetY)
                    val hit = laid.items.firstOrNull {
                        laid.lineOf(it) in shown &&
                            pos.x >= it.x && pos.x < it.x + it.width &&
                            pos.y >= it.lineTop && pos.y < it.lineTop + laid.hanTop + laid.hanHeight + lineGapPx
                    }?.takeIf { block.tokens[it.token].kind == TokenKind.HAN } ?: return@awaitEachGesture
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    if ((up.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                        up.consume()
                        onTap(hit.token)
                    }
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
                        color = if (isSaved) colors.saved else colors.pinyin,
                        topLeft = Offset(p.x + (inner - pl.size.width) / 2f, p.lineTop + laid.pinTop)
                    )
                }
                drawText(
                    p.hanLayout,
                    color = colors.text,
                    topLeft = Offset(p.x + (inner - hanW) / 2f, p.lineTop + laid.hanTop)
                )
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
    remember(measurer, a.fontSize, a.pinyinScale, spec, han) {
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
            glossStyle = TextStyle(fontSize = size * 0.42f, fontFamily = spec.body, color = spec.muted)
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
