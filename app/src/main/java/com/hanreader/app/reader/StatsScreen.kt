package com.hanreader.app.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hanreader.app.DailyActivity
import com.hanreader.app.LexiconIcons
import com.hanreader.app.Overline
import com.hanreader.app.Panel
import com.hanreader.app.ProgressBar
import com.hanreader.app.RussianText
import com.hanreader.app.isLearned

/** Reading time and words by day, the streak, how understanding of each book grows. */
@Composable
fun StatsScreen(vm: ReaderViewModel, onClose: () -> Unit) {
    BackHandler { onClose() }
    val days by vm.activity.collectAsState(initial = emptyList())
    val progress by vm.bookProgress.collectAsState(initial = emptyList())
    val cards by vm.allCards.collectAsState()
    val known by vm.knownWords.collectAsState()
    val library by vm.library.collectAsState()

    val today = ReadingStats.today()
    val range = remember(today) { ReadingStats.lastDays(today, 14) }
    val byDay = remember(days) { days.associateBy { it.day } }
    val todayRow = byDay[today] ?: DailyActivity(today)
    val streak = remember(days, today) { ReadingStats.streak(days, today) }
    val minutes = range.map { ((byDay[it]?.readMillis ?: 0L) / 60_000L).toInt() }
    val newWords = remember(cards, range) {
        val counts = cards.groupingBy { ReadingStats.dayOf(it.createdAt) }.eachCount()
        range.map { counts[it] ?: 0 }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(LexiconIcons.Back, contentDescription = "Назад") }
            Text("Статистика", style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Сегодня", "${todayRow.readMillis / 60_000} мин", "${todayRow.readChars} знаков", Modifier.weight(1f))
                    StatTile(
                        "Серия", "${streak.current} " + RussianText.plural(streak.current, "день", "дня", "дней"),
                        "рекорд ${streak.best}", Modifier.weight(1f)
                    )
                    StatTile("Повторения", "${todayRow.reviews}", "сегодня", Modifier.weight(1f))
                }
            }
            item {
                ChartPanel(
                    title = "Чтение за 14 дней, минут",
                    days = range,
                    values = minutes,
                    total = "Всего ${minutes.sum()} мин · ${range.sumOf { byDay[it]?.readChars ?: 0 }} знаков",
                    describe = { day, v -> "${ReadingStats.label(day)}: $v мин, ${byDay[day]?.readChars ?: 0} знаков" }
                )
            }
            item {
                ChartPanel(
                    title = "Новые слова в карточках",
                    days = range,
                    values = newWords,
                    total = "В карточках ${cards.size} · выучено ${cards.count { it.isLearned() }} · знакомых слов ${known.size}",
                    describe = { day, v -> "${ReadingStats.label(day)}: $v " + RussianText.plural(v, "слово", "слова", "слов") }
                )
            }
            val perBook = progress.groupBy { it.bookId }
            val books = library.filter { it.id in perBook }
            if (books.isNotEmpty()) {
                item { Overline("Понимание книг", Modifier.padding(top = 8.dp)) }
                books.forEach { book ->
                    val rows = perBook.getValue(book.id)
                    val first = rows.first()
                    val last = rows.last()
                    item(key = "book-${book.id}") {
                        Panel(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text("${last.percent}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                }
                                ProgressBar(last.percent / 100f, Modifier.fillMaxWidth())
                                val delta = last.percent - first.percent
                                Text(
                                    if (rows.size < 2 || first.day == last.day) "знакомых слов в тексте; рост появится после следующих дней чтения"
                                    else (if (delta >= 0) "+$delta%" else "$delta%") + " с ${ReadingStats.label(first.day)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A headline number with its label: no chart for a single value. */
@Composable
internal fun StatTile(label: String, value: String, note: String, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Overline(label)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * One series by day as thin bars in the theme's accent, rounded at the data end and anchored to
 * a recessive baseline. Labels stay in text colours: the largest value is labelled; a tap on a
 * day shows its value (the touch version of a hover tooltip).
 */
@Composable
internal fun ChartPanel(title: String, days: List<Int>, values: List<Int>, total: String, describe: (Int, Int) -> String) {
    var picked by remember(days) { mutableStateOf<Int?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val max = values.maxOrNull() ?: 0
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Overline(title)
            val p = picked
            Text(
                when {
                    p != null -> describe(days[p], values[p])
                    max > 0 -> "Больше всего: " + describe(days[values.indexOf(max)], max)
                    else -> "Пока пусто: данные появятся, когда начнёшь читать"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Canvas(
                Modifier.fillMaxWidth().height(120.dp).pointerInput(days) {
                    detectTapGestures { pos ->
                        val slot = size.width / days.size.toFloat()
                        val i = (pos.x / slot).toInt().coerceIn(0, days.lastIndex)
                        picked = if (picked == i) null else i
                    }
                }
            ) {
                val slot = size.width / days.size
                val barW = (slot * 0.56f).coerceAtMost(18.dp.toPx())
                val base = size.height - 1f
                val r = 4.dp.toPx()
                for ((i, v) in values.withIndex()) {
                    if (v <= 0 || max <= 0) continue
                    val h = (v.toFloat() / max * (size.height - 6.dp.toPx())).coerceAtLeast(2.dp.toPx())
                    val x = i * slot + (slot - barW) / 2
                    val color = if (p == null || p == i) accent else accent.copy(alpha = 0.4f)
                    // Rounded data end, square on the baseline.
                    drawRoundRect(color, Offset(x, base - h), Size(barW, h), CornerRadius(r, r))
                    if (h > r) drawRect(color, Offset(x, base - r), Size(barW, r))
                }
                drawLine(axis, Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
            }
            Row(Modifier.fillMaxWidth()) {
                Text(ReadingStats.label(days.first()), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(1f))
                Text("сегодня", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            Text(total, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}
