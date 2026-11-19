package com.hanreader.app.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hanreader.app.wiki.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WikipediaSheet(target: WikiNoteTarget, bookLanguage: String, source: WikipediaSource,
    onSave: suspend (WikiArticle, String) -> Unit, onDismiss: () -> Unit) {
    var query by remember(target) { mutableStateOf(target.lookupTerm.take(200)) }
    var language by remember(target) { mutableStateOf("ru") }
    var hits by remember(target) { mutableStateOf<List<WikiHit>?>(null) }
    var article by remember(target) { mutableStateOf<WikiArticle?>(null) }
    var definition by remember(target) { mutableStateOf("") }
    var message by remember(target) { mutableStateOf<String?>(null) }
    var busy by remember(target) { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun run(work: suspend () -> Unit) {
        val request = ++generation
        job?.cancel(); busy = true; message = null
        job = scope.launch {
            try { kotlinx.coroutines.withTimeout(20_000) { work() } }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { message = "Википедия не ответила вовремя. Попробуй ещё раз." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Не удалось получить данные Википедии. Проверь подключение или попробуй другой запрос." }
            finally { if (request == generation) busy = false }
        }
    }
    fun show(value: WikiArticle) { article = value; definition = value.extract; hits = null }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Википедия", style = MaterialTheme.typography.headlineSmall)
            Text("Передаётся только поисковый запрос. Остальной текст книги не отправляется.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { if (it.length <= 200) query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Термин или выражение") }, enabled = !busy, maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ru", bookLanguage.substringBefore('-')).distinct().filter { it in WikipediaRepository.languages }.forEach { tag ->
                    FilterChip(selected = language == tag, enabled = !busy, label = { Text(WikipediaRepository.languages.getValue(tag)) }, onClick = {
                        val from = article
                        language = tag
                        if (from == null) { hits = null; message = null }
                        else { article = null; run {
                            val translated = source.translation(from, tag)
                            if (translated == null) {
                                article = null; hits = null
                                message = "У этой статьи нет версии на выбранном языке. Можно изменить запрос и найти другую."
                            } else show(translated)
                        } }
                    })
                }
            }
            Button(enabled = !busy && query.isNotBlank(), onClick = {
                article = null; hits = null
                run { hits = source.search(language, query) }
            }) { Text("Найти") }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            val rows = hits
            if (rows != null && rows.isEmpty()) Text("Ничего не найдено. Попробуй другое название понятия.")
            rows?.forEach { hit ->
                OutlinedCard(onClick = { if (!busy) run { show(source.article(language, hit.id)) } }, enabled = !busy) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(hit.title, style = MaterialTheme.typography.titleMedium)
                        if (hit.snippet.isNotBlank()) Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            article?.let { selected ->
                Text(selected.title, style = MaterialTheme.typography.titleLarge)
                if (selected.ambiguous) {
                    Text("Это страница неоднозначности. Уточни запрос или открой оригинал, чтобы выбрать нужное понятие.")
                    Text(selected.extract)
                } else {
                    OutlinedTextField(definition, { if (it.length <= 12_000) definition = it },
                        Modifier.fillMaxWidth(), label = { Text("Описание для заметки") }, minLines = 4, enabled = !busy)
                    Text("Описание из вступления статьи; его можно сократить или дополнить.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && definition.isNotBlank(), onClick = {
                        run {
                            try { onSave(selected, definition); message = "Сохранено. Заметка доступна в оглавлении → «Выделения»." }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { message = "Не удалось сохранить заметку. Проверь, что книга ещё открыта, и повтори." }
                        }
                    }) { Text("Сохранить в заметки") }
                }
                Text("Википедия · CC BY-SA 4.0 · версия ${selected.revision}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(selected.revisionUrl))) }
                        .onFailure { message = "Не удалось открыть браузер." }
                }) { Text("Открыть оригинал") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = onDismiss) { Text("Вернуться к книге") }
        }
    }
}
