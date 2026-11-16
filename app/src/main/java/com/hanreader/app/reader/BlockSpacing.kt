package com.hanreader.app.reader

import com.hanreader.app.epub.EpubTextStyle

/** Same geometry in scroll and page modes; font em excludes pinyin/translation rows. */
internal fun blockSpacingPx(
    current: EpubTextStyle,
    previous: EpubTextStyle?,
    paragraphPx: Float,
    emPx: Float,
    atPageTop: Boolean = false
): Float {
    val regular = if (atPageTop || current.compactBefore) 0f else paragraphPx.coerceAtLeast(0f)
    // Adjacent authored margins collapse; scene separators survive even with paragraph spacing = 0.
    val authored = maxOf(previous?.spaceAfterEm ?: 0f, current.spaceBeforeEm)
        .takeIf { it.isFinite() }?.coerceIn(0f, 8f) ?: 0f
    return regular + authored * emPx.coerceAtLeast(0f)
}
