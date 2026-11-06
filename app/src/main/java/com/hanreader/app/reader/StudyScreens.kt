package com.hanreader.app.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hanreader.app.BookEntity
import com.hanreader.app.LexiconIcons
import com.hanreader.app.Overline
import com.hanreader.app.Panel
import com.hanreader.app.ProgressBar
import com.hanreader.app.WordFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SIZES = listOf(10, 20, 30)

/**
 * The list of words to study: the top [size] candidates are checked by default; each row can
 * be marked «Знаю» (known everywhere) or «Не учить» (hidden in this book).
 */
@Composable
private fun CandidatePicker(
    candidates: List<StudyCandidate>,
    countLabel: String,
    busy: Boolean,
    status: String?,
    onKnown: (StudyCandidate) -> Unit,
    onIgnore: (StudyCandidate) -> Unit,
    onAdd: (List<StudyCandidate>) -> Unit,
    modifier: Modifier = Modifier
) {
    var size by remember { mutableStateOf(10) }
    var unchecked by remember { mutableStateOf(setOf<String>()) }
    val shown = candidates.take(size)
    val chosen = shown.filter { it.word !in unchecked }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Самые частые:", style = MaterialTheme.typography.bodyMedium)
            SIZES.forEach { n ->
                FilterChip(selected = size == n, onClick = { size = n }, label = { Text("$n") })
            }
        }
        if (shown.isEmpty()) {
            Text(
                "Незнакомых слов для изучения не осталось.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        shown.forEach { c ->
            CandidateRow(
                c,
                countLabel = countLabel,
                checked = c.word !in unchecked,
                onCheck = { on -> unchecked = if (on) unchecked - c.word else unchecked + c.word },
                onKnown = { onKnown(c) },
                onIgnore = { onIgnore(c) }
            )
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            onClick = { if (!busy && chosen.isNotEmpty()) onAdd(chosen) },
            enabled = !busy && chosen.isNotEmpty(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (busy) "Добавляю…" else "Добавить в карточки: ${chosen.size}",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        Text(
            "Новые карточки входят в повторение по дневному лимиту новых слов (настройки во вкладке «Учить»).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun CandidateRow(
    c: StudyCandidate,
    countLabel: String,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    onKnown: () -> Unit,
    onIgnore: () -> Unit
) {
    Panel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheck)
            Column(Modifier.weight(1f).clickable { onCheck(!checked) }) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(c.word, fontFamily = WordFont, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Text(c.pinyin, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                Text(c.gloss, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$countLabel: ${c.count}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onKnown) { Text("Знаю") }
                TextButton(onClick = onIgnore) { Text("Не учить", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** «Подготовиться к разделу»: the chapter's most frequent unknown words. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareChapterSheet(vm: ReaderViewModel, book: OpenBook, chapter: ChapterContent, engine: ZhEngine, onDismiss: () -> Unit) {
    val known by vm.knownWords.collectAsState()
    val saved by vm.savedWords.collectAsState()
    val bookWords by vm.bookWords(book.entity.id).collectAsState(initial = emptyList())
    val ignored = remember(bookWords) { bookWords.filter { it.ignored }.mapTo(HashSet()) { it.word } }
    val candidates by produceState(emptyList<StudyCandidate>(), chapter, known, saved, ignored) {
        value = withContext(Dispatchers.Default) { VocabularyStats.chapter(chapter, known, saved, ignored, engine) }
    }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 700.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            item {
                Text("Подготовиться к разделу", style = MaterialTheme.typography.titleLarge)
                Text(
                    chapter.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                CandidatePicker(
                    candidates = candidates,
                    countLabel = "В разделе",
                    busy = busy,
                    status = status,
                    onKnown = { vm.setKnown(it.word, true) },
                    onIgnore = { vm.setIgnored(book.entity.id, it.word, true) },
                    onAdd = { list ->
                        busy = true
                        vm.addCandidates(book.entity, list, chapter) { r ->
                            busy = false
                            status = r.fold({ "Добавлено новых карточек: $it" }, { "Не удалось: ${it.message}" })
                        }
                    }
                )
            }
        }
    }
}

/** «Словарь книги»: how much of the book is known, what HSK covers, and what to learn first. */
@Composable
fun BookStatsScreen(vm: ReaderViewModel, bookId: Long, onClose: () -> Unit) {
    BackHandler { onClose() }
    val library by vm.library.collectAsState()
    val book = library.firstOrNull { it.id == bookId }
    val engine by vm.engine.collectAsState()
    val known by vm.knownWords.collectAsState()
    val saved by vm.savedWords.collectAsState()
    val progress by vm.analysis.collectAsState()
    val words by vm.bookWords(bookId).collectAsState(initial = emptyList())
    LaunchedEffect(book?.analyzedTokens) { if (book != null && book.analyzedTokens == 0) vm.analyzeBook(bookId) }
    val eng = engine
    val stats by produceState<BookStats?>(null, words, known, saved, eng) {
        value = if (eng == null || book == null || book.analyzedTokens == 0 || words.isEmpty()) null
        else withContext(Dispatchers.Default) { VocabularyStats.book(words, book.analyzedTokens, known, saved, eng) }
    }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(LexiconIcons.Back, contentDescription = "Назад") }
            Column(Modifier.weight(1f)) {
                Text("Словарь книги", style = MaterialTheme.typography.titleMedium)
                Text(
                    book?.title.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val s = stats
        val p = progress[bookId]
        if (s == null || book == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (p != null) {
                    Text("Разбираю книгу… ${(p * 100).toInt()}%")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                } else {
                    CircularProgressIndicator()
                }
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Ты знаешь ${s.knownPercent}% слов текста", style = MaterialTheme.typography.headlineSmall)
                        ProgressBar(s.knownPercent / 100f)
                        Text(
                            "Всего слов в тексте: ${s.tokens} · разных: ${s.distinct} · незнакомых разных: ${s.unknownDistinct}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Overline("Сложность относительно HSK 3.0")
                        val level95 = s.levelFor(95)
                        val level90 = s.levelFor(90)
                        Text(
                            when {
                                level90 == null -> "Даже словарь всех уровней HSK покрывает меньше 90% текста: много редких слов и имён."
                                level95 == null -> "Слова до HSK ${HskSystem.HSK3.levelTitle(level90)} покрывают 90% текста; 95% не набирается."
                                else -> "Для 95% знакомых слов нужен примерно HSK ${HskSystem.HSK3.levelTitle(level95)}."
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        s.levels.forEach { l ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("HSK ${HskSystem.HSK3.levelTitle(l.level)}", Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium)
                                ProgressBar(l.percent / 100f, Modifier.weight(1f))
                                Text("${l.percent}%", Modifier.width(48.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(
                            "Оценка по словам, а не официальный уровень: грамматику и имена она не учитывает.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Overline("Что выучить в первую очередь")
                Spacer(Modifier.height(8.dp))
                CandidatePicker(
                    candidates = s.candidates,
                    countLabel = "В книге",
                    busy = busy,
                    status = status,
                    onKnown = { vm.setKnown(it.word, true) },
                    onIgnore = { vm.setIgnored(bookId, it.word, true) },
                    onAdd = { list ->
                        busy = true
                        vm.addCandidates(book, list, null) { r ->
                            busy = false
                            status = r.fold({ "Добавлено новых карточек: $it" }, { "Не удалось: ${it.message}" })
                        }
                    }
                )
            }
        }
    }
}

/** Shown at the top of a chapter: how many unknown words it has, with a way to prepare. */
@Composable
fun ChapterPrepareBanner(unknown: Int, percent: Int, onPrepare: () -> Unit) {
    Surface(
        onClick = onPrepare,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Знакомо $percent% · незнакомых слов: $unknown", style = MaterialTheme.typography.bodyMedium)
                Text("Подготовиться к разделу →", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Library helper: a small "Словарь книги" action with the analysis progress. */
@Composable
fun BookVocabularyAction(book: BookEntity, progress: Float?, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            when {
                progress != null -> "Словарь… ${(progress * 100).toInt()}%"
                else -> "Словарь книги"
            }
        )
    }
}
