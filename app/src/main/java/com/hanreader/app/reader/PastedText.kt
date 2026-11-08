package com.hanreader.app.reader

/** Text that came from the clipboard, «Поделиться» or the text selection menu. */
object PastedText {
    /** Line ends unified, spaces at line ends dropped, at most one blank line in a row, no blank edges. */
    fun tidy(text: String): String = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace(' ', ' ')
        .lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** A title from the text: its first line, at most 30 characters. */
    fun title(text: String): String {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return if (first.length > 30) first.take(29) + "…" else first.ifEmpty { "Текст" }
    }
}
