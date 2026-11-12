package com.hanreader.app.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** A footnote mark is never drawn in the colour of the text. */
class NoteColorTest {
    private fun colors(text: Color, saved: Color) =
        RubyColors(text = text, pinyin = text, saved = saved, selection = text, wordGap = 3.dp, lineGap = 8.dp, unknownLine = Color.Red)

    @Test
    fun accentWhereItDiffersFromText() = assertEquals(Color.Blue, colors(Color.White, Color.Blue).note)

    @Test
    fun blackThemeWhoseAccentIsTheTextColour() = assertEquals(Color.Red, colors(Color.White, Color.White).note)
}
