package com.hanreader.app.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hanreader.app.lang.Languages
import com.hanreader.app.wiki.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WikipediaLibrary(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val engine by vm.engine.collectAsState()
    val known by vm.knownWords.collectAsState()
    val meanings by vm.userMeanings.collectAsState()
    val studying by vm.studyingWords.collectAsState()
    val levelMark by vm.levelMark.collectAsState()
    val pinyinMode by vm.pinyinMode.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<WikiDocument?>(null) }
    var saved by remember { mutableStateOf<List<WikiArticle>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val source = vm.wikipedia
    LaunchedEffect(source, reading, searching) {
        try { saved = source.savedArticles() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Не удалось прочитать сохранённые статьи" }
    }
    fun action(work: suspend () -> Unit) { scope.launch {
        busy = true; message = null
        try { withTimeout(20_000) { work() } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { message = "Не удалось открыть статью вовремя" }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Не удалось прочитать или изменить сохранённую статью" }
        finally { busy = false }
    } }
    if (searching) {
        val target = remember { WikiNoteTarget(0, -1, "", -1, 0, 0, "", "") }
        WikipediaSheet(target, Languages.current.tag, source, onSave = { _, _ -> }, onDismiss = { searching = false },
            engine = engine, known = known, notesEnabled = false, meanings = meanings, onKnown = vm::setKnown, studying = studying, levelMark = levelMark, pinyinMode = pinyinMode, onPinyinMode = vm::setPinyinMode)
        return
    }
    reading?.let { document -> WikipediaReader(document, source, engine, known, onDismiss = { reading = null }, meanings = meanings, onKnown = vm::setKnown, studying = studying, levelMark = levelMark, pinyinMode = pinyinMode, onPinyinMode = vm::setPinyinMode); return }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Википедия", style = MaterialTheme.typography.headlineSmall)
            Text("Читай статьи со словарными подсказками и сохраняй их без интернета.")
            Button(enabled = !busy, onClick = { searching = true }) { Text("Найти статью") }
            Text("Сохранённые статьи", style = MaterialTheme.typography.titleMedium)
            Text("До 30 статей: до 20 МБ текста и до 80 МБ изображений. Статьи хранятся отдельно от книг и пока не входят в ручную резервную копию.", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp)) {
            if (saved.isEmpty()) item { Text("Здесь появятся статьи, для которых ты выбрал «Сохранить офлайн».", Modifier.padding(24.dp)) }
            items(saved, key = { "${it.language}:${it.id}:${it.revision}" }) { article ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(article.title, style = MaterialTheme.typography.titleMedium)
                    Text("${WikipediaRepository.languages[article.language]} · версия ${article.revision}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = !busy, onClick = { action { reading = source.fullArticle(article) } }) { Text("Читать") }
                        TextButton(enabled = !busy, onClick = { action { source.removeSavedArticle(article); saved = source.savedArticles() } }) { Text("Удалить с устройства") }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
