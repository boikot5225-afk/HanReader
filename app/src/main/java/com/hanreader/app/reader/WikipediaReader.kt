package com.hanreader.app.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import com.hanreader.app.lang.PreparedText
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.wiki.*
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uses the book reader's segmentation, geometry and word rendering; never opens or closes its book. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WikipediaReader(document: WikiDocument, source: WikipediaSource, engine: LanguageModule?, known: Set<String>,
    onDismiss: () -> Unit, onSaveExcerpt: (suspend (WikiArticle, String) -> Unit)? = null, meanings: Map<String, String> = emptyMap()) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(document.key) { focus.clearFocus(force = true); keyboard?.hide() }
    val prefs = remember(context) { context.getSharedPreferences("wiki_reading_positions", android.content.Context.MODE_PRIVATE) }
    val appAppearance by AppearanceStore.appearance.collectAsState()
    var fontSize by remember(document.key) { mutableIntStateOf(prefs.getInt("fontSize", appAppearance.fontSize).coerceIn(16, 36)) }
    var hints by remember(document.key) { mutableStateOf(true) }
    var contents by remember(document.key) { mutableStateOf(false) }
    var sourceDetails by remember(document.key) { mutableStateOf(false) }
    var selected by remember(document.key) { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(document.key) { mutableStateOf<String?>(null) }
    var offline by remember(document.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dictionary = engine?.takeIf { it.info.tag.substringBefore('-') == document.article.language }
    val globalMeanings = remember(meanings, dictionary) { if (dictionary == null) emptyMap() else meanings.filterKeys { !ContextMeanings.isScoped(it) } }
    val appearance = appAppearance.copy(fontSize = fontSize)
    val spec = LocalThemeSpec.current
    val metrics = rememberRubyMetrics(rememberTextMeasurer(cacheSize = 0), appearance, spec, WordFont)
    val colors = rubyColors(appearance, spec)
    val initial = remember(document.key) { prefs.getInt(document.key, 0).coerceIn(0, document.blocks.size) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    // A paragraph index, not a page number or pixel offset: font changes keep the saved source place.
    LaunchedEffect(document.key, list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { prefs.edit().putInt(document.key, it).apply() }
    }
    LaunchedEffect(document.key, source) {
        offline = runCatching { source.savedArticles().any { it.language == document.article.language && it.id == document.article.id && it.revision == document.article.revision } }.getOrDefault(false)
    }
    var prepare by remember(document.key, dictionary, appearance.script) { mutableStateOf<((String) -> PreparedText)?>(null) }
    LaunchedEffect(document.key, dictionary, appearance.script) {
        prepare = withContext(Dispatchers.Default) {
            dictionary?.chapter(document.blocks.map { it.text }, TextOptions(script = appearance.script)) ?: ::plainWikiText
        }
    }
    fun action(work: suspend () -> Unit) { scope.launch {
        busy = true; message = null
        try { work() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Не удалось сохранить. Проверь свободное место и доступность исходной книги." }
        finally { busy = false }
    } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextButton(onClick = onDismiss) { Text("← Назад") }
                    TextButton(enabled = !busy && !offline, onClick = { action { source.saveArticle(document); offline = true; message = "Статья сохранена для чтения без интернета" } }) {
                        Text(if (offline) "Сохранена офлайн" else "Сохранить офлайн")
                    }
                }
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(enabled = fontSize > 16, onClick = { fontSize -= 2; prefs.edit().putInt("fontSize", fontSize).apply() }) { Text("А−") }
                    IconButton(enabled = fontSize < 36, onClick = { fontSize += 2; prefs.edit().putInt("fontSize", fontSize).apply() }) { Text("А+") }
                    TextButton(onClick = { contents = !contents }) { Text("Разделы") }
                    if (dictionary != null) TextButton(onClick = { hints = !hints }) { Text(if (hints) "Подсказки: вкл." else "Подсказки: выкл.") }
                }
                if (contents) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    itemsIndexed(document.blocks) { index, block -> if (block.heading > 0) {
                        TextButton(onClick = { contents = false; scope.launch { list.scrollToItem(index + 1) } }) { Text(block.text) }
                    } }
                }
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("wiki-article-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(key = "source") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(document.article.title, style = MaterialTheme.typography.headlineSmall)
                            Text("Википедия · ${WikipediaRepository.languages[document.article.language]}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { sourceDetails = !sourceDetails }) { Text(if (sourceDetails) "Скрыть сведения" else "Об источнике") }
                            if (dictionary == null && document.article.language != "ru") Text(
                                if (engine == null && com.hanreader.app.lang.Languages.current.tag.substringBefore('-') == document.article.language) "Загружаю словарь…"
                                else "Для словарных подсказок выбери этот язык в библиотеке приложения.", style = MaterialTheme.typography.bodySmall)
                            if (sourceDetails) {
                                Text("Версия ${document.article.revision} · CC BY-SA 4.0. Текстовая версия: без изображений и интерактивных элементов. Строки таблиц показаны текстом.", style = MaterialTheme.typography.bodySmall)
                                FlowRow {
                                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(document.article.revisionUrl))) } }) { Text("Оригинал") }
                                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(document.article.historyUrl))) } }) { Text("Авторы и правки") }
                                }
                            }
                        }
                    }
                    itemsIndexed(document.blocks, key = { index, _ -> index }) { index, block ->
                        val preparing = prepare
                        if (preparing == null) Text(block.text)
                        else WikiParagraph(block, index, preparing, dictionary, known, globalMeanings, hints, metrics, colors,
                            selected?.takeIf { it.first == index }?.let { it.second until it.third },
                            onSelect = { start, end -> selected = Triple(index, start, end); message = null })
                    }
                    item(key = "attribution") {
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
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun WikiParagraph(block: WikiBlock, index: Int, prepare: (String) -> PreparedText, engine: LanguageModule?,
    known: Set<String>, meanings: Map<String, String>, hints: Boolean, metrics: RubyMetrics, colors: RubyColors, selected: IntRange?, onSelect: (Int, Int) -> Unit) {
    val prepared by produceState<PreparedText?>(null, block, prepare) { value = withContext(Dispatchers.Default) { ensureActive(); prepare(block.text) } }
    val preparedText = prepared
    if (preparedText == null) { Text(block.text); return }
    val readerBlock = remember(preparedText, block.heading) { ReaderBlock.Text(preparedText.text, block.heading > 0, preparedText.tokens, preparedText.shown) }
    val glosses by produceState<Map<String, String>>(emptyMap(), preparedText, engine, known, hints, meanings) {
        value = if (!hints || engine == null) emptyMap() else withContext(Dispatchers.Default) {
            val matcher = KnownMatcher(known, engine)
            readerBlock.tokens.filter { it.kind == TokenKind.HAN }.mapNotNull { token ->
                val own = meanings[ContextMeanings.keyOf(token, engine)]
                (own ?: engine.glossShort(token).takeIf { !matcher.isKnown(token.text) })?.let { "@$index:${token.start}" to it }
            }.toMap()
        }
    }
    RubyParagraph(readerBlock, metrics, hints && engine is ZhEngine, null, emptySet(), selected?.let { Highlights.range(readerBlock, it.first, it.last + 1) }, colors,
        modifier = Modifier.testTag("wiki-block-$index").semantics {
            contentDescription = block.text
            onClick(label = "Выбрать абзац") { onSelect(0, block.text.length); true }
        },
        onTap = { token -> readerBlock.tokens[token].let { onSelect(it.start, it.end) } },
        onRange = { a, b, done -> if (done) onSelect(readerBlock.tokens[minOf(a, b)].start, readerBlock.tokens[maxOf(a, b)].end) },
        glossFor = if (hints && engine != null && block.heading == 0) glosses else null, blockIndex = index)
}

private fun plainWikiText(text: String): PreparedText {
    val tokens = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’−-][\\p{L}\\p{M}\\p{N}]+)*|\\s+|[^\\p{L}\\p{M}\\p{N}\\s]").findAll(text).map { match ->
        val word = match.value
        RToken(word, "", if (word.any(Char::isLetterOrDigit)) TokenKind.HAN else if (word.isBlank()) TokenKind.SPACE else TokenKind.PUNCT,
            match.range.first, match.range.last + 1, false)
    }.toList()
    return PreparedText(text, text, tokens)
}
