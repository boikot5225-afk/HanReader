package com.hanreader.app.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanreader.app.LexiconIcons
import com.hanreader.app.Panel
import com.hanreader.app.ReaderSave
import com.hanreader.app.Speaker
import com.hanreader.app.WordFont
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Tapped word: paragraph index and token range, which the user can widen or narrow. */
private data class Selection(val block: Int, val first: Int, val last: Int)

private val FONT_SIZES = listOf(18, 20, 22, 24, 27, 30)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel, onClose: () -> Unit) {
    val open by vm.open.collectAsState()
    val saved by vm.savedWords.collectAsState()
    val engine by vm.engine.collectAsState()
    val book = open ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val context = LocalContext.current
    val speaker = remember(context) { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.close() } }

    var showPinyin by rememberSaveable { mutableStateOf(true) }
    var fontStep by rememberSaveable { mutableStateOf(2) }
    var showToc by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    BackHandler { onClose() }

    val fontSize = FONT_SIZES[fontStep].sp
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val scheme = MaterialTheme.colorScheme
    val metrics = remember(fontSize, scheme) {
        val base = androidx.compose.ui.text.TextStyle(fontSize = fontSize, fontFamily = WordFont, color = scheme.onBackground)
        RubyMetrics(
            measurer,
            hanStyle = base,
            pinyinStyle = base.copy(fontSize = fontSize * 0.5f, fontFamily = null, color = scheme.onSurfaceVariant),
            headingStyle = base.copy(fontSize = fontSize * 1.25f, fontWeight = FontWeight.SemiBold)
        )
    }
    val colors = RubyColors(
        text = scheme.onBackground,
        pinyin = scheme.onSurfaceVariant,
        saved = scheme.primary,
        selection = scheme.secondaryContainer,
        wordGap = 3.dp,
        lineGap = 8.dp
    )

    val chapter = book.chapter
    val listState = rememberLazyListState()
    // Restore the saved position once per chapter, then keep saving it while scrolling.
    LaunchedEffect(chapter?.index) {
        val c = chapter ?: return@LaunchedEffect
        val target = if (c.index == book.entity.chapterIndex) book.entity.blockIndex else 0
        listState.scrollToItem(target.coerceIn(0, (c.blocks.size).coerceAtLeast(0)))
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(600)
            .collect { vm.savePosition(c.index, it) }
    }

    Column(Modifier.fillMaxSize().background(scheme.background)) {
        ReaderTopBar(
            title = chapter?.title ?: book.entity.title,
            subtitle = book.entity.title,
            showPinyin = showPinyin,
            onBack = onClose,
            onToc = { showToc = true },
            onPinyin = { showPinyin = !showPinyin },
            onSmaller = { if (fontStep > 0) fontStep-- },
            onBigger = { if (fontStep < FONT_SIZES.lastIndex) fontStep++ }
        )
        HorizontalDivider(color = scheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                book.error != null -> Text(book.error, Modifier.padding(24.dp), color = scheme.error)
                chapter == null || book.loading && chapter.index != book.entity.chapterIndex -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (engine == null) "Загружаю словарь…" else "Открываю главу…",
                        color = scheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(chapter.blocks, key = { i, _ -> "${chapter.index}:$i" }) { i, block ->
                        when (block) {
                            is ReaderBlock.Text -> RubyParagraph(
                                block = block,
                                metrics = metrics,
                                showPinyin = showPinyin,
                                saved = saved,
                                selection = selection?.takeIf { it.block == i }?.let { it.first..it.last },
                                colors = colors,
                                onTap = { t -> selection = Selection(i, t, t) }
                            )
                            is ReaderBlock.Image -> BookImage(vm, block.path)
                        }
                    }
                    item(key = "footer:${chapter.index}") {
                        ChapterFooter(
                            chapter = chapter,
                            count = book.epub.chapters.size,
                            engineMs = engine?.loadMs,
                            onPrev = { vm.goToChapter(chapter.index - 1) },
                            onNext = { vm.goToChapter(chapter.index + 1) }
                        )
                    }
                }
            }
        }
    }

    if (showToc) {
        TocSheet(book, onDismiss = { showToc = false }) { index ->
            showToc = false
            selection = null
            vm.goToChapter(index)
        }
    }

    val sel = selection
    val block = sel?.let { chapter?.blocks?.getOrNull(it.block) as? ReaderBlock.Text }
    val eng = engine
    if (sel != null && block != null && eng != null && chapter != null) {
        WordSheet(
            selection = sel,
            block = block,
            engine = eng,
            saved = saved,
            speaker = speaker,
            onChange = { selection = it },
            onDismiss = { selection = null },
            onSave = { word, reading, translation, sentence, done ->
                vm.saveWord(
                    ReaderSave(
                        word = word,
                        reading = reading,
                        translation = translation,
                        sentence = sentence,
                        bookId = book.entity.id,
                        bookTitle = book.entity.title,
                        chapterIndex = chapter.index,
                        chapterTitle = chapter.title
                    ),
                    done
                )
            }
        )
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String,
    showPinyin: Boolean,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onPinyin: () -> Unit,
    onSmaller: () -> Unit,
    onBigger: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(LexiconIcons.Back, contentDescription = "Назад в библиотеку") }
        Column(Modifier.weight(1f).clickable(onClick = onToc).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onToc) { Icon(LexiconIcons.ListBullets, contentDescription = "Оглавление") }
        TextButton(onClick = onPinyin) {
            Text(
                "pīn",
                color = if (showPinyin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (showPinyin) FontWeight.Bold else FontWeight.Normal
            )
        }
        TextButton(onClick = onSmaller) { Text("A−") }
        TextButton(onClick = onBigger) { Text("A+") }
    }
}

@Composable
private fun ChapterFooter(chapter: ChapterContent, count: Int, engineMs: Long?, onPrev: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPrev, enabled = chapter.index > 0) { Text("← Назад") }
            OutlinedButton(onClick = onNext, enabled = chapter.index < count - 1) { Text("Дальше →") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Раздел ${chapter.index + 1} из $count · ${chapter.chars} знаков · разбор ${chapter.parseMs} мс" +
                (engineMs?.let { " · словарь $it мс" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BookImage(vm: ReaderViewModel, path: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        val bitmap by produceState<android.graphics.Bitmap?>(null, path, maxPx) { value = vm.image(path, maxPx) }
        val bmp = bitmap
        if (bmp != null) {
            val density = LocalDensity.current
            val w = with(density) { minOf(bmp.width, maxPx).toDp() }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(
                    bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.width(w).heightIn(max = 520.dp),
                    contentScale = ContentScale.Fit
                )
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocSheet(book: OpenBook, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val current = book.chapter?.index ?: book.entity.chapterIndex
    val entries = book.epub.toc.ifEmpty {
        book.epub.chapters.map { com.hanreader.app.epub.TocEntry(it.title, it.index, 0) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val start = entries.indexOfLast { it.chapterIndex <= current }.coerceAtLeast(0)
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
        Text("Оглавление", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            itemsIndexed(entries) { i, e ->
                val isCurrent = i == start
                Text(
                    e.title,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(e.chapterIndex) }
                        .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .padding(start = (24 + 16 * e.depth.coerceAtMost(3)).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    fontFamily = WordFont,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordSheet(
    selection: Selection,
    block: ReaderBlock.Text,
    engine: ZhEngine,
    saved: Set<String>,
    speaker: Speaker,
    onChange: (Selection) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, (Result<Boolean>) -> Unit) -> Unit
) {
    val tokens = block.tokens
    val first = tokens[selection.first]
    val last = tokens[selection.last]
    val word = block.text.substring(first.start, last.end)
    val entry = remember(word) { engine.lookup(word)?.takeIf { it.hasDefinition } }
    val chars = remember(word) { if (entry == null) engine.characters(word) else emptyList() }
    val reading = entry?.pinyin ?: tokens.subList(selection.first, selection.last + 1).joinToString(" ") { it.pinyin }
    val translation = entry?.shortGloss()
        ?: chars.joinToString(" + ") { it.shortGloss(1) }.ifEmpty { "нет в словаре" }
    val sentence = remember(word, selection) { ChapterBuilder.sentence(block.text, first.start, last.end) }
    var status by remember(word) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isSaved = word in saved

    // Neighbouring Han tokens for widening the selection.
    val canGrowLeft = selection.first > 0 && tokens[selection.first - 1].kind == TokenKind.HAN
    val canGrowRight = selection.last < tokens.lastIndex && tokens[selection.last + 1].kind == TokenKind.HAN
    val canShrink = selection.last > selection.first

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(word, fontFamily = WordFont, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Text(reading, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            if (entry != null) {
                entry.gloss.split("; ", "/").map { it.trim() }.filter { it.isNotEmpty() }.take(6).forEachIndexed { i, sense ->
                    Text("${i + 1}. $sense", style = MaterialTheme.typography.bodyLarge)
                }
                if (entry.rank > 0) {
                    Text("Частотность: №${entry.rank}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Слова нет в словаре. По иероглифам:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                chars.forEach { c ->
                    Text("${c.word}  ${c.pinyin} — ${c.shortGloss(2)}", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onChange(selection.copy(first = selection.first - 1)) }, enabled = canGrowLeft) { Text("← +") }
                OutlinedButton(onClick = { onChange(selection.copy(last = selection.last - 1)) }, enabled = canShrink) { Text("−") }
                OutlinedButton(onClick = { onChange(selection.copy(last = selection.last + 1)) }, enabled = canGrowRight) { Text("+ →") }
            }
            Text(
                "Границы слова: расширь выделение, если словарь разрезал сочетание.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Panel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text(sentence, Modifier.padding(14.dp), fontFamily = WordFont, style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { speaker.speak(word, STUDY_LANGUAGE) }) {
                    Icon(LexiconIcons.Volume, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Слово")
                }
                OutlinedButton(onClick = { speaker.speak(sentence, STUDY_LANGUAGE) }) {
                    Icon(LexiconIcons.Volume, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Фраза")
                }
            }
            Surface(
                onClick = {
                    onSave(word, reading, translation, sentence) { r ->
                        scope.launch {
                            status = r.fold(
                                onSuccess = { created -> if (created) "Добавлено в карточки" else "Уже в карточках: добавлен новый пример" },
                                onFailure = { "Не удалось сохранить: ${it.message}" }
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (isSaved) "Добавить пример к карточке" else "Сохранить в карточки", style = MaterialTheme.typography.titleMedium)
                }
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            speaker.message.takeIf { it.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
