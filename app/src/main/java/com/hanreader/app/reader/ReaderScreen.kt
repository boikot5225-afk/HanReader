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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.hanreader.app.AppearanceStore
import com.hanreader.app.LocalThemeSpec
import com.hanreader.app.WordFont
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Tapped word: paragraph index and token range, which the user can widen or narrow. */
private data class Selection(val block: Int, val first: Int, val last: Int)


@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel, onClose: () -> Unit) {
    val open by vm.open.collectAsState()
    val saved by vm.savedWords.collectAsState()
    val engine by vm.engine.collectAsState()
    val known by vm.knownWords.collectAsState()
    val marked by vm.markedKnown.collectAsState()
    val pinyinMode by vm.pinyinMode.collectAsState()
    val book = open ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val context = LocalContext.current
    val speaker = remember(context) { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.close() } }

    val appearance by AppearanceStore.appearance.collectAsState()
    var showAppearance by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showPrepare by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    BackHandler { onClose() }

    val spec = LocalThemeSpec.current
    val scheme = MaterialTheme.colorScheme
    val hanFamily = WordFont
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val metrics = rememberRubyMetrics(measurer, appearance, spec, hanFamily)
    val colors = rubyColors(appearance, spec)

    val chapter = book.chapter
    val matcher = remember(known, engine) { KnownMatcher(known, engine) }
    // Words of this chapter treated as known (marked, learned or transparent compounds of them).
    val knownHere = remember(chapter, matcher) { chapter?.let { matcher.knownIn(it) } ?: emptySet() }
    val bookWords by vm.bookWords(book.entity.id).collectAsState(initial = emptyList())
    val names = remember(bookWords) { bookWords.filter { it.ignored }.mapTo(HashSet()) { it.word } }
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
            pinyinMode = pinyinMode,
            onBack = onClose,
            onToc = { showToc = true },
            onPinyin = vm::setPinyinMode,
            appearanceOpen = showAppearance,
            onAppearance = { showAppearance = true }
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
                    contentPadding = PaddingValues(horizontal = appearance.margin.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(appearance.paragraphSpacing.dp)
                ) {
                    item(key = "prepare:${chapter.index}") {
                        val coverage = remember(chapter, matcher, names) { KnownCoverage.of(chapter, matcher, names, engine) }
                        if (engine != null && coverage.total > 0 && coverage.unknownDistinct > 0) {
                            ChapterPrepareBanner(coverage.unknownDistinct, coverage.percent) { showPrepare = true }
                        }
                    }
                    itemsIndexed(chapter.blocks, key = { i, _ -> "${chapter.index}:$i" }) { i, block ->
                        when (block) {
                            is ReaderBlock.Text -> RubyParagraph(
                                block = block,
                                metrics = metrics,
                                showPinyin = pinyinMode != PinyinMode.OFF,
                                hidePinyinFor = if (pinyinMode == PinyinMode.UNKNOWN) knownHere else null,
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
                            matcher = matcher,
                            names = names,
                            engine = engine,
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

    val prepEngine = engine
    if (showPrepare && chapter != null && prepEngine != null) {
        PrepareChapterSheet(vm, book, chapter, prepEngine, onDismiss = { showPrepare = false })
    }

    if (showAppearance) {
        AppearanceSheet(onDismiss = { showAppearance = false })
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
            known = known,
            marked = marked,
            speaker = speaker,
            onKnown = vm::setKnown,
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
    pinyinMode: PinyinMode,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onPinyin: (PinyinMode) -> Unit,
    appearanceOpen: Boolean,
    onAppearance: () -> Unit
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
        PinyinMenu(pinyinMode, onPinyin)
        TextButton(onClick = onAppearance) {
            Text("Aa", fontFamily = WordFont, color = if (appearanceOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun PinyinMenu(mode: PinyinMode, onPick: (PinyinMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                when (mode) {
                    PinyinMode.ALL -> "pīn"
                    PinyinMode.UNKNOWN -> "pīn·?"
                    PinyinMode.OFF -> "pīn"
                },
                color = if (mode == PinyinMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                fontWeight = if (mode == PinyinMode.OFF) FontWeight.Normal else FontWeight.Bold
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Пиньинь",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PinyinMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = { open = false; onPick(option) },
                    trailingIcon = {
                        if (option == mode) Icon(LexiconIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                )
            }
        }
    }
}

@Composable
private fun ChapterFooter(
    chapter: ChapterContent,
    matcher: KnownMatcher,
    names: Set<String>,
    engine: ZhEngine?,
    count: Int,
    engineMs: Long?,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val coverage = remember(chapter, matcher, names) { KnownCoverage.of(chapter, matcher, names, engine) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPrev, enabled = chapter.index > 0) { Text("← Назад") }
            OutlinedButton(onClick = onNext, enabled = chapter.index < count - 1) { Text("Дальше →") }
        }
        Spacer(Modifier.height(12.dp))
        if (coverage.total > 0) {
            Text(
                "Знакомо ${coverage.percent}% слов раздела · незнакомых разных слов: ${coverage.unknownDistinct}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
        }
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
    known: Set<String>,
    marked: Set<String>,
    speaker: Speaker,
    onKnown: (String, Boolean) -> Unit,
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
    val russian = remember(word) { engine.russianSenses(word) }
    val translation = engine.cardTranslation(word)
        ?: chars.joinToString(" + ") { engine.cardTranslation(it.word) ?: it.shortGloss(1) }.ifEmpty { "нет в словаре" }
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
            if (russian.isNotEmpty()) {
                russian.forEachIndexed { i, sense ->
                    Text("${i + 1}. $sense", style = MaterialTheme.typography.bodyLarge)
                }
                engine.russianSource(word)?.let { src ->
                    Text(
                        "Русский · ${src.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (entry != null) {
                if (russian.isNotEmpty()) {
                    Text("English", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                entry.gloss.split("; ", "/").map { it.trim() }.filter { it.isNotEmpty() }
                    .take(if (russian.isEmpty()) 6 else 3).forEachIndexed { i, sense ->
                    Text(
                        "${i + 1}. $sense",
                        style = if (russian.isEmpty()) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = if (russian.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.rank > 0) {
                    Text("Частотность: №${entry.rank}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Слова нет в словаре. По иероглифам:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                chars.forEach { c ->
                    Text("${c.word}  ${c.pinyin} — ${engine.cardTranslation(c.word) ?: c.shortGloss(2)}", style = MaterialTheme.typography.bodyLarge)
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
            val isMarked = word in marked
            val learnedInCards = word in known && !isMarked
            OutlinedButton(
                onClick = { onKnown(word, !isMarked) },
                enabled = !learnedInCards,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (isMarked) {
                    Icon(LexiconIcons.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        learnedInCards -> "Выучено в карточках"
                        isMarked -> "Знаю · нажми, чтобы снять"
                        else -> "Знаю это слово"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            speaker.message.takeIf { it.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
