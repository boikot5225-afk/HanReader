package com.hanreader.app.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hanreader.app.Highlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Search only the saved notes; opening this sheet never parses the book or starts a request. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookKnowledgeSheet(vm: ReaderViewModel, book: OpenBook, highlights: List<Highlight>,
    onDismiss: () -> Unit, onQuote: (Highlight) -> Unit, onExport: (List<BookKnowledge.Entry>) -> Unit, initialEntry: Long? = null) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialEntry) }
    var wiki by remember { mutableStateOf<WikiNoteTarget?>(null) }
    var message by remember(selected) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attach by remember(selected) { mutableStateOf(false) }
    var editing by remember(selected) { mutableStateOf(false) }
    var draft by remember(selected) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val all = remember(highlights, book.entity.id) { BookKnowledge.entries(book.entity.id, highlights) }
    val filtered = remember(highlights, book.entity.id, query) { BookKnowledge.entries(book.entity.id, highlights, query) }
    val entry = all.firstOrNull { it.record.id == selected }
    val wide = remember(highlights) { highlights.filter { it.bookId == book.entity.id && it.chapterIndex == Highlights.BOOK_WIDE } }
    fun change(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { action(); message = "Сохранено" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Не удалось сохранить изменение. Проверь, что книга и запись ещё доступны." }
            finally { busy = false }
        }
    }
    val target = wiki
    if (target != null) {
        val id = selected
        val engine by vm.engine.collectAsState()
        val known by vm.knownWords.collectAsState()
        val meanings by vm.userMeanings.collectAsState()
        val studying by vm.studyingWords.collectAsState()
        val levelMark by vm.levelMark.collectAsState()
        val pinyinMode by vm.pinyinMode.collectAsState()
        WikipediaSheet(target, com.hanreader.app.lang.Languages.current.tag, vm.wikipedia,
            engine = engine, known = known, meanings = meanings, onKnown = vm::setKnown, studying = studying, levelMark = levelMark, pinyinMode = pinyinMode, onPinyinMode = vm::setPinyinMode,
            onSave = { article, definition -> check(id != null); vm.saveKnowledgeWikipedia(id, article, definition) },
            onDismiss = { wiki = null })
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (entry == null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text("Люди и понятия", style = MaterialTheme.typography.headlineSmall)
                Text("Общие записи, объяснения и сохранённые фрагменты этой книги.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, label = { Text("Найти в записях") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                TextButton(enabled = all.isNotEmpty(), onClick = { onExport(all) }) { Text("Экспортировать все записи") }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 480.dp)) {
                if (filtered.isEmpty()) item { Text(if (all.isEmpty()) "Сохрани объяснение из Википедии или создай общую запись в карточке слова. К ней можно прикреплять фрагменты книги."
                    else "Ничего не найдено", Modifier.padding(24.dp)) }
                items(filtered, key = { it.record.id }) { e ->
                    Column(Modifier.fillMaxWidth().clickable { selected = e.record.id }.padding(horizontal = 24.dp, vertical = 14.dp)) {
                        Text(e.record.text, style = MaterialTheme.typography.titleMedium)
                        if (e.variants.isNotEmpty()) Text(e.variants.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        Text(if (e.shared) "Фрагментов: ${e.quotes.size}" else "Заметка этого места", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().heightIn(max = 660.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = null; editing = false; attach = false }) { Text("← Все записи") }
                Text(entry.record.text, style = MaterialTheme.typography.headlineSmall)
                Text(if (entry.shared) "Общая запись книги" else "Заметка этого места", style = MaterialTheme.typography.labelLarge)
                if (editing) {
                    OutlinedTextField(draft, { if (it.length <= 50_000) draft = it }, label = { Text("Текст заметки") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10)
                    TextButton(enabled = !busy, onClick = { change { vm.saveKnowledgeNote(entry.record.id, draft); editing = false } }) { Text("Сохранить заметку") }
                } else {
                    if (entry.record.note.isNotBlank()) Text(entry.record.note)
                    TextButton(onClick = { draft = entry.record.note; editing = true }) { Text("Изменить заметку") }
                }
                Regex("https://(?:ru|es|en|zh)\\.wikipedia\\.org/w/index\\.php\\?oldid=\\d+").findAll(entry.record.note).map { it.value }.distinct().forEach { url ->
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("Открыть сохранённый источник в Википедии") }
                }
                TextButton(enabled = !busy, onClick = {
                    val h = entry.record
                    wiki = WikiNoteTarget(h.bookId, h.chapterIndex, h.chapterTitle, h.blockIndex, h.startChar, h.endChar, h.text,
                        if (entry.shared) "" else BookKnowledge.quote(h))
                }) { Text("Найти объяснение в Википедии") }
                if (entry.shared) EntityLinksPanel(highlights, entry.record, entry.record.text, false,
                    onLink = { text, id, done -> vm.linkEntity(text, id, onDone = done) }, onUnlink = vm::unlinkEntity, canUnlink = false, onUseSaved = vm::linkSavedEntity)
                else {
                    TextButton(enabled = !busy && entry.record.text.length <= 200, onClick = { change { selected = vm.promoteKnowledgeNote(entry.record.id) } }) { Text("Сделать общей записью книги") }
                    TextButton(enabled = !busy && EntityLinks.roots(wide).isNotEmpty(), onClick = { attach = !attach }) { Text("Прикрепить к общей записи…") }
                    if (attach) EntityLinks.roots(wide).forEach { owner -> TextButton(enabled = !busy, onClick = {
                        change { vm.attachExistingQuote(entry.record.id, owner.id); selected = owner.id; attach = false }
                    }) { Text(owner.text) } }
                }
                TextButton(onClick = { onExport(listOf(entry)) }) { Text("Экспортировать запись") }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (entry.quotes.isNotEmpty()) Text("Сохранённые фрагменты", style = MaterialTheme.typography.titleMedium)
                entry.quotes.forEach { q ->
                    HorizontalDivider()
                    Text(q.chapterTitle.ifBlank { "Раздел ${q.chapterIndex + 1}" }, style = MaterialTheme.typography.labelLarge)
                    Text(BookKnowledge.quote(q))
                    if (q.id != entry.record.id && q.note.isNotBlank()) Text(q.note, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onQuote(q) }) { Text("Перейти к месту в книге") }
                    if (entry.shared) TextButton(enabled = !busy, onClick = { change { vm.detachEntityQuote(q.id) } }) { Text("Отвязать, сохранив фрагмент") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Saving a passage does not declare its complete text to be an alias. */
@Composable
internal fun EntityQuoteAction(entries: List<Highlight>, current: Highlight?, onSave: suspend (Long) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(id: Long) { scope.launch {
        busy = true
        try { onSave(id); choosing = false; message = "Фрагмент сохранён в «Люди и понятия»" }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Не удалось сохранить фрагмент. Выбери его заново и попробуй ещё раз." }
        finally { busy = false }
    } }
    Column {
        TextButton(enabled = !busy, onClick = { if (current != null) save(current.id) else choosing = !choosing }) {
            Text(if (current != null) "Сохранить фрагмент к записи" else "Сохранить фрагмент о человеке или понятии…")
        }
        if (choosing) {
            val roots = EntityLinks.roots(entries)
            if (roots.isEmpty()) Text("Сначала создай общую запись для имени или понятия через «Это тот же человек или термин…».", style = MaterialTheme.typography.bodySmall)
            else roots.forEach { h -> TextButton(enabled = !busy, onClick = { save(h.id) }) { Text(h.text) } }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
