package com.hanreader.app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hanreader.app.Highlight
import com.hanreader.app.LexiconIcons
import com.hanreader.app.WordFont

object Highlights {
    /** Yellow, green, blue, pink: the colour index stored with a highlight. */
    val COLORS = listOf(Color(0xFFF2C94C), Color(0xFF6FCF97), Color(0xFF56B4F2), Color(0xFFF28AB2))
    val NAMES = listOf("жёлтый", "зелёный", "синий", "розовый")

    fun color(i: Int) = COLORS[i.coerceIn(0, COLORS.lastIndex)]

    /** Tokens of [block] covered by [list] (token -> colour) and the tokens that end a noted highlight. */
    fun tokens(block: ReaderBlock.Text, list: List<Highlight>?): Pair<Map<Int, Color>, Set<Int>> {
        if (list.isNullOrEmpty()) return emptyMap<Int, Color>() to emptySet()
        val marked = HashMap<Int, Color>()
        val noted = HashSet<Int>()
        for (h in list) {
            val range = range(block, h.startChar, h.endChar) ?: continue
            for (k in range) marked[k] = color(h.color)
            if (h.note.isNotBlank()) noted += range.last
        }
        return marked to noted
    }

    /** Tokens overlapping characters [start, end). */
    fun range(block: ReaderBlock.Text, start: Int, end: Int): IntRange? {
        val first = block.tokens.indexOfFirst { it.end > start }
        val last = block.tokens.indexOfLast { it.start < end }
        return if (first in 0..last) first..last else null
    }

    /** The highlight under characters [start, end) of paragraph [block], if any. */
    fun at(list: List<Highlight>, chapter: Int, block: Int, start: Int, end: Int): Highlight? =
        list.firstOrNull { it.chapterIndex == chapter && it.blockIndex == block && it.startChar < end && it.endChar > start }

    /** Tokens of the sentence around tokens [first..last]. */
    fun sentence(block: ReaderBlock.Text, first: Int, last: Int): IntRange {
        val text = block.text
        var s = block.tokens[first].start
        while (s > 0 && text[s - 1] !in SENTENCE_END) s--
        var e = block.tokens[last].end
        while (e < text.length && text[e] !in SENTENCE_END) e++
        if (e < text.length) e++
        while (e < text.length && text[e] in "”’」』\"") e++
        val r = range(block, s, e) ?: return first..last
        // Leading spaces and closing quotes of the previous sentence stay out.
        var a = r.first
        while (a < r.last && block.tokens[a].text.isBlank()) a++
        return a..r.last
    }

    private const val SENTENCE_END = "。！？!?…；;\n"

    /** All highlights of a book as Markdown: chapters as headings, passages as quotes, notes under them. */
    fun markdown(title: String, author: String?, list: List<Highlight>): String = buildString {
        append("# ").append(title).append('\n')
        if (!author.isNullOrBlank()) append('\n').append(author).append('\n')
        append("\nВыделения и заметки из HanReader: ").append(list.size).append('\n')
        var chapter = -1
        for (h in list.sortedWith(compareBy({ it.chapterIndex }, { it.blockIndex }, { it.startChar }))) {
            if (h.chapterIndex != chapter) {
                chapter = h.chapterIndex
                append("\n## ").append(h.chapterTitle.ifBlank { "Раздел ${h.chapterIndex + 1}" }).append('\n')
            }
            append('\n')
            h.text.trim().lines().forEach { append("> ").append(it).append('\n') }
            if (h.note.isNotBlank()) append('\n').append(h.note.trim()).append('\n')
        }
    }
}

/** Colours to highlight the selected words, their note, a way to take the whole sentence. */
@Composable
internal fun HighlightPanel(
    current: Highlight?,
    canWiden: Boolean,
    onColor: (Int) -> Unit,
    onSentence: () -> Unit,
    onNote: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (current == null) "Выделить" else "Выделено", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(12.dp))
            Highlights.COLORS.forEachIndexed { i, c ->
                val selected = current?.color == i
                Box(
                    Modifier.padding(horizontal = 4.dp).size(30.dp).clip(CircleShape).background(c)
                        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { onColor(i) }
                )
            }
            Spacer(Modifier.weight(1f))
            if (current != null) {
                IconButton(onClick = onRemove) {
                    Icon(LexiconIcons.Trash, contentDescription = "Убрать выделение", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (canWiden && current == null) {
            TextButton(onClick = onSentence, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                Text("Взять всё предложение")
            }
        }
        if (current != null) {
            var note by remember(current.id) { mutableStateOf(current.note) }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Заметка") },
                shape = RoundedCornerShape(14.dp),
                minLines = 1,
                maxLines = 5
            )
            if (note != current.note) {
                TextButton(onClick = { onNote(note) }) { Text("Сохранить заметку") }
            }
        }
    }
}

/** The book's highlights; a tap opens the place, the export button shares them as Markdown. */
@Composable
internal fun HighlightList(
    list: List<Highlight>,
    totalChapters: Int,
    onPick: (Highlight) -> Unit,
    onRemove: (Highlight) -> Unit,
    onExport: () -> Unit
) {
    if (list.isEmpty()) {
        Text(
            "Выделений пока нет. Нажми на слово в тексте и выбери цвет; «Взять всё предложение» выделит фразу целиком.",
            Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${list.size} · заметок ${list.count { it.note.isNotBlank() }}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = onExport) { Text("Экспорт в Markdown") }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(list, key = { it.id }) { h ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(h) }.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(width = 4.dp, height = 40.dp).background(Highlights.color(h.color), RoundedCornerShape(2.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            "Раздел ${h.chapterIndex + 1} из $totalChapters · ${h.chapterTitle}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(h.text, fontFamily = WordFont, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (h.note.isNotBlank()) Text(h.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onRemove(h) }) {
                        Icon(LexiconIcons.Trash, contentDescription = "Удалить выделение", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
