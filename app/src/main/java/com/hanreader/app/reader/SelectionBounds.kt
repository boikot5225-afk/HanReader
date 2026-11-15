package com.hanreader.app.reader

import com.hanreader.app.zh.TokenKind

/** Adjacent words may be separated by horizontal space, never punctuation or a new paragraph. */
internal object SelectionBounds {
    fun neighbour(tokens: List<RToken>, from: Int, direction: Int): Int? {
        require(direction == -1 || direction == 1)
        var i = from + direction
        while (i in tokens.indices && tokens[i].kind == TokenKind.SPACE) {
            if (tokens[i].text.any { it == '\n' || it == '\r' }) return null
            i += direction
        }
        return i.takeIf { tokens.getOrNull(it)?.kind == TokenKind.HAN }
    }

    fun shrink(tokens: List<RToken>, range: IntRange, left: Boolean): IntRange? {
        if (range.first >= range.last) return null
        val edge = neighbour(tokens, if (left) range.first else range.last, if (left) 1 else -1) ?: return null
        return (if (left) edge..range.last else range.first..edge).takeIf { it.first <= it.last }
    }
}
