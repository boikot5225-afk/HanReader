package com.hanreader.app.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import com.hanreader.app.BigButton
import com.hanreader.app.BookEntity
import com.hanreader.app.LexiconIcons
import com.hanreader.app.Overline
import com.hanreader.app.Panel
import com.hanreader.app.ProgressBar
import com.hanreader.app.LocalThemeSpec
import com.hanreader.app.WordFont
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign

/** File types the picker offers: EPUB, FB2 (also zipped) and plain text. */
val BOOK_MIME_TYPES = arrayOf(
    "application/epub+zip", "application/x-fictionbook+xml", "application/x-fictionbook", "text/xml",
    "text/plain", "application/zip", "application/x-zip-compressed", "application/octet-stream",
    "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
)

@Composable
fun LibraryScreen(vm: ReaderViewModel, padding: PaddingValues, onOpen: (Long) -> Unit, onStats: (Long) -> Unit, onReadingStats: () -> Unit = {}, onPaste: () -> Unit = {}) {
    val all by vm.library.collectAsState()
    val sort by vm.sort.collectAsState()
    val grid by vm.grid.collectAsState()
    val books = remember(all, sort) { vm.sorted(all, sort) }
    val analysis by vm.analysis.collectAsState()
    // The latest share of known words in each book (its understanding, from the statistics).
    val progressRows by vm.bookProgress.collectAsState(initial = emptyList())
    val knownShare = remember(progressRows) { progressRows.groupBy { it.bookId }.mapValues { (_, r) -> r.maxBy { it.day }.percent } }
    val known by vm.knownWords.collectAsState()
    val levelMark by vm.levelMark.collectAsState()
    var showLevel by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<BookEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            error = null
            vm.importBook(uri) { r ->
                importing = false
                r.onSuccess { onOpen(it) }.onFailure { error = it.message ?: "Не удалось добавить книгу" }
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            KnownWordsPanel(known.size, levelMark, onChoose = { showLevel = true })
        }
        item { ActivityPanel(vm, onReadingStats) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Overline("Моя библиотека", Modifier.weight(1f))
                if (books.isNotEmpty()) {
                    SortMenu(sort, vm::setSort)
                    IconButton(onClick = { vm.setGrid(!grid) }) {
                        Icon(
                            if (grid) LexiconIcons.ListBullets else LexiconIcons.Library,
                            contentDescription = if (grid) "Показать списком" else "Показать обложками"
                        )
                    }
                }
            }
        }
        if (books.isEmpty()) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Добавь китайскую книгу: EPUB, FB2, TXT или документ Word", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Над словами появится пиньинь. Нажми на слово — увидишь перевод и сохранишь его в карточки вместе с предложением из книги. Книгу можно открыть и прямо из файлового менеджера или Telegram: «Открыть с помощью» → HanReader.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (grid) {
            items(books.chunked(3), key = { row -> row.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { book ->
                        BookTile(vm, book, analysis[book.id], knownShare[book.id], Modifier.weight(1f), onOpen = { onOpen(book.id) }, onStats = { onStats(book.id) }, onDelete = { confirmDelete = book })
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            items(books, key = { it.id }) { book ->
                BookRow(
                    vm,
                    book,
                    progress = analysis[book.id],
                    known = knownShare[book.id],
                    onOpen = { onOpen(book.id) },
                    onStats = { onStats(book.id) },
                    onDelete = { confirmDelete = book }
                )
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            if (importing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text("Добавляю книгу…")
                }
            } else {
                BigButton("Добавить книгу", onClick = { picker.launch(BOOK_MIME_TYPES) })
                androidx.compose.material3.TextButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) { Text("Вставить текст") }
                Text(
                    "EPUB, FB2, FB2.ZIP, TXT, DOCX, DOC",
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            error?.let {
                Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showLevel) LevelDialog(vm, levelMark, onClose = { showLevel = false })

    confirmDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить книгу?") },
            text = { Text("«${book.title}» исчезнет из библиотеки. Сохранённые из неё слова и карточки останутся.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteBook(book); confirmDelete = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun BookRow(vm: ReaderViewModel, book: BookEntity, progress: Float?, known: Int?, onOpen: () -> Unit, onStats: () -> Unit, onDelete: () -> Unit) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(LocalThemeSpec.current.corner), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BookCover(vm, book, Modifier.width(64.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, fontFamily = WordFont, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) {
                    Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                ProgressBar(readFraction(book))
                Text(
                    if (book.lastOpenedAt == 0L) "Ещё не открыта · ${book.chapterCount} разделов"
                    else "Раздел ${book.chapterIndex + 1} из ${book.chapterCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                known?.let { DifficultyLabel(it) }
                BookVocabularyAction(book, progress, onStats)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(LexiconIcons.Trash, contentDescription = "Удалить книгу", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun readFraction(book: BookEntity) = if (book.chapterCount > 0 && book.lastOpenedAt > 0) (book.chapterIndex + 1f) / book.chapterCount else 0f

/** A book in the cover grid; long press offers the book's vocabulary and deleting. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(vm: ReaderViewModel, book: BookEntity, progress: Float?, known: Int?, modifier: Modifier, onOpen: () -> Unit, onStats: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            BookCover(
                vm, book,
                Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onClickLabel = "Открыть", onLongClickLabel = "Действия")
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Словарь книги") }, onClick = { menu = false; onStats() })
                DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
        ProgressBar(readFraction(book))
        Text(book.title, fontFamily = WordFont, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (progress != null) {
            Text("Словарь… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else known?.let { DifficultyLabel(it, short = true) }
    }
}

/** «Для тебя: 97% · комфортно»: how much of the book the reader knows and what that means for reading. */
@Composable
private fun DifficultyLabel(percent: Int, short: Boolean = false) {
    val d = ReadingStats.Difficulty.of(percent)
    val color = when (d) {
        ReadingStats.Difficulty.COMFORTABLE, ReadingStats.Difficulty.WITH_HINTS -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        (if (short) "" else "Для тебя: ") + "$percent% · ${d.title}",
        style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1
    )
}

/** The book's own cover, or one drawn from its title in the theme's colours. */
@Composable
fun BookCover(vm: ReaderViewModel, book: BookEntity, modifier: Modifier = Modifier) {
    val spec = LocalThemeSpec.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, book.id) { value = vm.cover(book) }
    val shape = RoundedCornerShape(if (spec.corner > 0.dp) 8.dp else 2.dp)
    Box(
        modifier.aspectRatio(0.68f).clip(shape).background(spec.surfaceHigh)
            .border(1.dp, spec.border, shape),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Column(Modifier.matchParentSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.width(24.dp).height(3.dp).background(spec.accent))
                Text(book.title, color = spec.text, fontFamily = WordFont, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(book.author, color = spec.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SortMenu(sort: ReaderViewModel.LibrarySort, onPick: (ReaderViewModel.LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(sort.title) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReaderViewModel.LibrarySort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.title) },
                    onClick = { open = false; onPick(s) },
                    trailingIcon = { if (s == sort) Icon(LexiconIcons.Check, contentDescription = "Выбрано", tint = MaterialTheme.colorScheme.primary) }
                )
            }
        }
    }
}

@Composable
private fun KnownWordsPanel(count: Int, mark: LevelMark, onChoose: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Знакомые слова: $count", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (mark.level == 0) "Отметь свой уровень — пиньинь останется только над новыми словами"
                    else "Отмечено до ${mark.system.title} ${mark.system.levelTitle(mark.level)} и слова «Знаю»",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onChoose) { Text(if (mark.level == 0) "Уровень" else "Изменить") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelDialog(vm: ReaderViewModel, current: LevelMark, onClose: () -> Unit) {
    var system by remember { mutableStateOf(current.system) }
    var level by remember { mutableStateOf(current.level) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Какие слова ты уже знаешь?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Все слова до выбранного уровня станут знакомыми. Слова, отмеченные вручную, не изменятся.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HskSystem.entries.forEach { s ->
                        FilterChip(selected = system == s, onClick = {
                            system = s
                            if (level !in s.levels) level = s.levels.last()
                        }, label = { Text(s.title) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = level == 0, onClick = { level = 0 }, label = { Text("Начинаю") })
                    system.levels.forEach { l ->
                        FilterChip(selected = level == l, onClick = { level = l }, label = { Text(system.levelTitle(l)) })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                vm.setLevel(system, level) { r ->
                    busy = false
                    r.onSuccess { onClose() }.onFailure { error = it.message ?: "Не получилось" }
                }
            }) { Text(if (busy) "Сохраняю…" else "Готово") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Отмена") } }
    )
}

/** Today's reading and the streak; opens the statistics. */
@Composable
private fun ActivityPanel(vm: ReaderViewModel, onOpen: () -> Unit) {
    val days by vm.activity.collectAsState(initial = emptyList())
    val today = ReadingStats.today()
    val row = days.firstOrNull { it.day == today }
    val streak = remember(days, today) { ReadingStats.streak(days, today) }
    Panel(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Overline("Статистика")
                Text(
                    "Сегодня ${(row?.readMillis ?: 0L) / 60_000} мин · серия ${streak.current} " +
                        com.hanreader.app.RussianText.plural(streak.current, "день", "дня", "дней") +
                        days.sumOf { it.readChars.toLong() }.let { if (it > 0) " · прочитано ${ReadingStats.amount(it)}" else "" },
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Icon(LexiconIcons.ChevronDown, contentDescription = null, modifier = Modifier.graphicsLayer(rotationZ = -90f))
        }
    }
}
