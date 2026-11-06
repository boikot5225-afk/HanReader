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
import com.hanreader.app.BigButton
import com.hanreader.app.BookEntity
import com.hanreader.app.LexiconIcons
import com.hanreader.app.Overline
import com.hanreader.app.Panel
import com.hanreader.app.ProgressBar
import com.hanreader.app.WordFont

@Composable
fun LibraryScreen(vm: ReaderViewModel, padding: PaddingValues, onOpen: (Long) -> Unit, onStats: (Long) -> Unit) {
    val books by vm.library.collectAsState()
    val analysis by vm.analysis.collectAsState()
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
            Overline("Моя библиотека")
        }
        item {
            KnownWordsPanel(known.size, levelMark, onChoose = { showLevel = true })
        }
        if (books.isEmpty()) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Добавь китайскую книгу в формате EPUB", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Над словами появится пиньинь. Нажми на слово — увидишь перевод и сохранишь его в карточки вместе с предложением из книги.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        items(books, key = { it.id }) { book ->
            BookRow(
                book,
                progress = analysis[book.id],
                onOpen = { onOpen(book.id) },
                onStats = { onStats(book.id) },
                onDelete = { confirmDelete = book }
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            if (importing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text("Добавляю книгу…")
                }
            } else {
                BigButton("Добавить EPUB", onClick = {
                    picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
                })
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
private fun BookRow(book: BookEntity, progress: Float?, onOpen: () -> Unit, onStats: () -> Unit, onDelete: () -> Unit) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, fontFamily = WordFont, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) {
                    Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val progress = if (book.chapterCount > 0) (book.chapterIndex + 1f) / book.chapterCount else 0f
                Spacer(Modifier.height(4.dp))
                ProgressBar(progress)
                Text(
                    if (book.lastOpenedAt == 0L) "Ещё не открыта · ${book.chapterCount} разделов"
                    else "Раздел ${book.chapterIndex + 1} из ${book.chapterCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BookVocabularyAction(book, progress, onStats)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) {
                Icon(LexiconIcons.Trash, contentDescription = "Удалить книгу", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
