package com.hanreader.app.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hanreader.app.*
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.wiki.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uses the book reader's segmentation, geometry and word rendering; never opens or closes its book. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WikipediaReader(initialDocument: WikiDocument, source: WikipediaSource, engine: LanguageModule?, known: Set<String>,
    onDismiss: () -> Unit, onSaveExcerpt: (suspend (WikiArticle, String) -> Unit)? = null, meanings: Map<String, String> = emptyMap(),
    onKnown: ((String, Boolean) -> Unit)? = null, studying: Set<String> = emptySet(),
    levelMark: LevelMark = LevelMark(HskSystem.HSK3, 0), pinyinMode: PinyinMode = PinyinMode.UNKNOWN,
    onPinyinMode: ((PinyinMode) -> Unit)? = null) {
    var document by remember(initialDocument) { mutableStateOf(initialDocument) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(document.key) { focus.clearFocus(force = true); keyboard?.hide() }
    val prefs = remember(context) { context.getSharedPreferences("wiki_reading_positions", android.content.Context.MODE_PRIVATE) }
    val appAppearance by AppearanceStore.appearance.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var pinyinSettings by remember { mutableStateOf(false) }
    val hints = appAppearance.gloss != GlossMode.OFF
    var contents by remember(document.key) { mutableStateOf(false) }
    var sourceDetails by remember(document.key) { mutableStateOf(false) }
    var selected by remember(document.key) { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(document.key) { mutableStateOf<String?>(null) }
    var offline by remember(document.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dictionary = engine?.takeIf { it.info.tag.substringBefore('-') == document.article.language }
    val globalMeanings = remember(meanings, dictionary) { if (dictionary == null) emptyMap() else meanings.filterKeys { !ContextMeanings.isScoped(it) } }
    val appearance = appAppearance
    val fontSize = appearance.fontSize
    val spec = LocalThemeSpec.current
    val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), appearance, spec, WordFont)
    val colors = rubyColors(appearance, spec)
    var position by remember(document.key) { mutableStateOf(
        prefs.getInt("${document.key}:block", (prefs.getInt(document.key, 0) - 1).coerceAtLeast(0)).coerceIn(document.blocks.indices) to
            prefs.getInt("${document.key}:char", 0).coerceAtLeast(0)) }
    var scrollAnchor by remember(document.key) { mutableIntStateOf(prefs.getInt(document.key, 0).coerceIn(0, document.blocks.size)) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = scrollAnchor)
    fun savePosition(block: Int, char: Int) {
        position = block.coerceIn(document.blocks.indices) to char.coerceIn(0, document.blocks[block.coerceIn(document.blocks.indices)].text.length)
        prefs.edit().putInt("${document.key}:block", position.first).putInt("${document.key}:char", position.second).apply()
    }
    LaunchedEffect(document.key, appearance.mode) {
        if (appearance.mode == ReadingMode.SCROLL) {
            list.scrollToItem(scrollAnchor)
            snapshotFlow { list.firstVisibleItemIndex }.collect { index ->
                scrollAnchor = index
                prefs.edit().putInt(document.key, index).apply()
                savePosition((index - 1).coerceAtLeast(0), 0)
            }
        }
    }
    LaunchedEffect(document.key, source) {
        offline = runCatching { source.savedArticles().any { it.language == document.article.language && it.id == document.article.id && it.revision == document.article.revision } }.getOrDefault(false)
    }
    var reading by remember(document, dictionary, appearance.script) { mutableStateOf<WikiReading?>(null) }
    var preparationFailed by remember(document.key) { mutableStateOf(false) }
    var retry by remember(document.key) { mutableIntStateOf(0) }
    LaunchedEffect(document, dictionary, appearance.script, retry) {
        preparationFailed = false
        try {
            reading = withContext(Dispatchers.Default) { kotlinx.coroutines.withTimeout(READER_LOAD_TIMEOUT_MS) {
                WikiReading.prepare(document, dictionary, TextOptions(script = appearance.script))
            } }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { preparationFailed = true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { preparationFailed = true }
    }
    val ready = reading
    val learning by produceState(WikiLearning(), ready, dictionary, known, globalMeanings, appearance, studying, levelMark, pinyinMode) {
        value = if (ready == null) WikiLearning() else withContext(Dispatchers.Default) {
            wikiLearning(ready, dictionary, known, globalMeanings, appearance, studying, levelMark, pinyinMode)
        }
    }
    val showPinyin = dictionary is ZhEngine && pinyinMode != PinyinMode.OFF
    val hidePinyinFor = remember(ready, learning, pinyinMode) { when (pinyinMode) {
        PinyinMode.ALL, PinyinMode.OFF -> null
        PinyinMode.UNKNOWN -> learning.knownHere
        PinyinMode.RECOMMENDED -> ready?.chapter?.blocks?.flatMap { (it as? ReaderBlock.Text)?.tokens.orEmpty() }?.mapTo(HashSet()) { it.text }?.minus(learning.recommended) ?: emptySet()
    } }
    val glosses = if (hints && dictionary != null && appearance.gloss != GlossMode.OFF) learning.glosses else null
    var jump by remember { mutableStateOf<((Int, Int) -> Unit)?>(null) }
    val paragraph: @Composable (ReaderBlock.Text, Int, IntRange?) -> Unit = { block, layout, lines ->
        val index = ready!!.source(layout)
        RubyParagraph(block, metrics, showPinyin, hidePinyinFor, studying,
            selected?.takeIf { it.first == index }?.let { Highlights.range(block, it.second, it.third) }, colors,
            modifier = Modifier.testTag("wiki-block-$index").semantics {
                contentDescription = document.blocks[index].text
                onClick(label = "Выбрать абзац") { selected = Triple(index, 0, document.blocks[index].text.length); true }
            },
            lines = lines, unknownMarks = if (appearance.markUnknown) learning.unknown else null,
            recommendedMarks = if (appearance.markRecommended) learning.recommended else null,
            noMarks = remember(block, dictionary) { dictionary?.let { VocabularyStats.nameTokens(block, emptySet(), it) } ?: emptySet() },
            onTap = { token -> block.tokens[token].let { selected = Triple(index, it.start, it.end); message = null } },
            onRange = { a, b, done -> if (done) selected = Triple(index, block.tokens[minOf(a, b)].start, block.tokens[maxOf(a, b)].end) },
            glossFor = if (block.heading) null else glosses, blockIndex = index)
    }
    fun action(failure: String = "Не удалось сохранить. Проверь свободное место и доступность исходной книги.", work: suspend () -> Unit) { scope.launch {
        busy = true; message = null
        try { work() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = failure }
        finally { busy = false }
    } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val view = androidx.compose.ui.platform.LocalView.current
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            DisposableEffect(view, appearance.keepScreenOn) {
                val previous = view.keepScreenOn; view.keepScreenOn = appearance.keepScreenOn
                onDispose { view.keepScreenOn = previous }
            }
            DisposableEffect(window, appearance.brightness) {
                val previous = window?.attributes?.screenBrightness
                window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = appearance.brightness } }
                onDispose { if (previous != null) window.attributes = window.attributes.apply { screenBrightness = previous } }
            }
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextButton(onClick = onDismiss) { Text("← Назад") }
                    TextButton(enabled = !busy && (!offline || document.images.isNotEmpty()), onClick = { action {
                        source.saveArticle(document); offline = true
                        val result = source.saveImages(document)
                        message = if (result.total == 0) "Статья сохранена для чтения без интернета"
                            else "Текст сохранён. Изображения офлайн: ${result.saved} из ${result.total}." + if (result.saved < result.total) " Нажми «Сохранена офлайн», чтобы повторить загрузку." else ""
                    } }) {
                        Text(if (offline) "Сохранена офлайн" else "Сохранить офлайн")
                    }
                }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(enabled = fontSize > 16, onClick = { AppearanceStore.update { it.copy(fontSize = (it.fontSize - 2).coerceAtLeast(16)) } }) { Text("А−") }
                    IconButton(enabled = fontSize < 36, onClick = { AppearanceStore.update { it.copy(fontSize = (it.fontSize + 2).coerceAtMost(36)) } }) { Text("А+") }
                    TextButton(onClick = { settings = true }) { Text("Оформление") }
                    TextButton(onClick = { sourceDetails = !sourceDetails }) { Text("Об источнике") }
                    TextButton(onClick = { contents = !contents }) { Text("Разделы") }
                    if (dictionary is ZhEngine && onPinyinMode != null) TextButton(onClick = { pinyinSettings = true }) { Text("Пиньинь") }
                    if (dictionary != null) TextButton(onClick = { AppearanceStore.update { it.copy(gloss = if (hints) GlossMode.OFF else GlossMode.UNKNOWN) } }) { Text(if (hints) "Подсказки: вкл." else "Подсказки: выкл.") }
                }
                if (contents) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    itemsIndexed(document.blocks) { index, block -> if (block.heading > 0) {
                        TextButton(onClick = { contents = false; if (appearance.mode == ReadingMode.PAGES) ready?.let { jump?.invoke(it.layoutBlocks[index], 0) } else scope.launch { list.scrollToItem(index + 1) } }) { Text(block.text) }
                    } }
                }
                if (sourceDetails) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("Википедия · версия ${document.article.revision}. Текст — CC BY-SA 4.0; у изображений свои авторы и условия использования.", style = MaterialTheme.typography.bodySmall)
                    if (document.images.isEmpty()) TextButton(enabled = !busy, onClick = {
                        action("Не удалось загрузить иллюстрации. Сохранённый текст остаётся доступен.") {
                            val illustrated = kotlinx.coroutines.withTimeout(20_000) { source.withIllustrations(document) }
                            if (offline) source.saveArticle(illustrated)
                            document = illustrated
                            message = if (illustrated.images.isEmpty()) "В этой версии статьи нет доступных иллюстраций."
                                else if (offline) source.saveImages(illustrated).let { "Изображения офлайн: ${it.saved} из ${it.total}." }
                                else "Иллюстрации загружены. Их можно сохранить вместе со статьёй."
                        }
                    }) { Text("Загрузить иллюстрации") }
                    FlowRow {
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(document.article.revisionUrl))) } }) { Text("Оригинал") }
                        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(document.article.historyUrl))) } }) { Text("Авторы и правки") }
                    }
                }
                if (ready == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (preparationFailed) TextButton(onClick = { retry++ }) { Text("Повторить подготовку статьи") } else CircularProgressIndicator()
                }
                else if (appearance.mode == ReadingMode.PAGES) Box(Modifier.weight(1f).fillMaxWidth().testTag("wiki-pages")) {
                    PagedChapter(null, ready.book(position.first, position.second), ready.chapter, metrics, colors, appearance,
                        showPinyin, hidePinyinFor, false, glosses, banner = {}, paragraph = paragraph, bars = PaddingValues(0.dp), speed = 600f,
                        keysEnabled = appearance.volumeKeys && !settings && !pinyinSettings && selected == null,
                        onTapCenter = { contents = !contents }, onAnyTap = { if (selected != null) { selected = null; true } else false },
                        onProgress = {}, registerJump = {}, registerBlockJump = { jump = it }, bookmarkedBlocks = emptySet(), onCancelLoading = onDismiss,
                        onPosition = { _, layout, char -> savePosition(ready.source(layout), char); scrollAnchor = position.first + 1; prefs.edit().putInt(document.key, scrollAnchor).apply() },
                        imageAspect = { path -> document.images[path.toInt()].let { it.height.toFloat() / it.width + .3f } },
                        imageContent = { path -> WikipediaImage(document, document.images[path.toInt()], source, paged = true) })
                }
                else LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("wiki-article-list"), contentPadding = PaddingValues(horizontal = appearance.margin.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(appearance.paragraphSpacing.dp)) {
                    item(key = "source") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(document.article.title, style = MaterialTheme.typography.headlineSmall)
                            Text("Википедия · ${WikipediaRepository.languages[document.article.language]}", style = MaterialTheme.typography.bodySmall)
                            if (dictionary == null && document.article.language != "ru") Text(
                                "Для словарных подсказок выбери этот язык в библиотеке приложения.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    itemsIndexed(document.blocks, key = { index, _ -> index }) { index, block ->
                        Column {
                            document.images.filter { it.beforeBlock == index }.forEach { WikipediaImage(document, it, source) }
                            val layout = ready.layoutBlocks[index]
                            paragraph(ready.chapter.blocks[layout] as ReaderBlock.Text, layout, null)
                        }
                    }
                    item(key = "attribution") {
                        document.images.filter { it.beforeBlock == document.blocks.size }.forEach { WikipediaImage(document, it, source) }
                        Text("Авторы: участники Википедии. История правок: ${document.article.historyUrl}\nЛицензия: https://creativecommons.org/licenses/by-sa/4.0/\nПолучено: ${java.time.Instant.ofEpochMilli(document.article.fetchedAt)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                selected?.let { selection ->
                    val block = document.blocks[selection.first]
                    val text = block.text.substring(selection.second, selection.third)
                    val translation by produceState<String?>(null, document.key, dictionary, text, globalMeanings) {
                        value = withContext(Dispatchers.Default) { globalMeanings[dictionary?.lemma(text)] ?: dictionary?.cardTranslation(text) }
                    }
                    Surface(tonalElevation = 8.dp) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text.take(400), maxLines = 3, style = MaterialTheme.typography.titleMedium)
                            if (dictionary != null && (translation != null || text.none(Char::isWhitespace))) Text(translation ?: "Словарного значения нет", maxLines = 3)
                            if (dictionary != null && onKnown != null && text.length <= 200) {
                                val token = ready?.chapter?.blocks?.get(ready.layoutBlocks[selection.first])?.let { it as? ReaderBlock.Text }?.tokens
                                    ?.firstOrNull { it.start == selection.second && it.end == selection.third }
                                val key = token?.let { ChapterSummary.keyOf(it, dictionary) } ?: dictionary.lemma(text)
                                val isKnown = remember(known, key, dictionary) { KnownMatcher(known, dictionary).isKnown(key) }
                                if (token != null) KnownToggle(isKnown, { value -> onKnown(key, value) })
                            }
                            FlowRow {
                                TextButton(onClick = { selected = Triple(selection.first, 0, block.text.length) }) { Text("Весь абзац") }
                                if (onSaveExcerpt != null) TextButton(enabled = !busy && text.length <= 12_000, onClick = {
                                    action { onSaveExcerpt(document.article.copy(extract = text), text); message = "Сохранено в заметку исходной книги" }
                                }) { Text("В заметку книги") }
                                TextButton(onClick = { selected = null }) { Text("Закрыть") }
                            }
                        }
                    }
                }
                if (pinyinSettings) AlertDialog(onDismissRequest = { pinyinSettings = false }, title = { Text("Пиньинь") },
                    text = { Column { PinyinMode.entries.forEach { mode ->
                        TextButton(onClick = { onPinyinMode?.invoke(mode); pinyinSettings = false }) { Text(mode.title) }
                    } } }, confirmButton = { TextButton(onClick = { pinyinSettings = false }) { Text("Закрыть") } })
                if (settings) AppearanceSheet(onDismiss = { settings = false }, showChapterTools = false)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
