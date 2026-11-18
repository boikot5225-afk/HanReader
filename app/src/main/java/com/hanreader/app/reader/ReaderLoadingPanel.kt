package com.hanreader.app.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hanreader.app.lang.Languages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberReaderReportSaver(): (ReaderLoadState) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportText by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val report = exportText
            val ok = withContext(Dispatchers.IO) { runCatching {
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("output")
                out.bufferedWriter().use { it.write(report) }
            }.isSuccess }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, if (ok) "Отчёт сохранён." else "Не удалось сохранить отчёт. Выбери другое место.",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    return { state ->
        exportText = readerLoadReport(state, Languages.current.tag)
        save.launch("amauta-loading-report.txt")
    }
}

@Composable
internal fun ReaderLoadingPanel(
    state: ReaderLoadState,
    onCancel: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onScroll: (() -> Unit)? = null,
    initialLabel: String? = null,
    onSaveReport: ((ReaderLoadState) -> Unit)? = null
) {
    val saveReport = onSaveReport ?: rememberReaderReportSaver()
    var elapsed by remember(state.id) { mutableLongStateOf(state.elapsed()) }
    LaunchedEffect(state.id, state.failure) {
        while (true) { elapsed = state.elapsed(); if (state.failure != null) break; delay(1000) }
    }
    val slow = elapsed >= READER_LOAD_SLOW_MS
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.failure == null) CircularProgressIndicator()
            Text(state.failure?.label ?: initialLabel ?: state.stage.label)
            if (state.failure != null) Text("Этап: ${state.stage.label.removeSuffix("…")}", style = MaterialTheme.typography.bodySmall)
            if (slow && state.failure == null) Text("Это дольше обычного. Можно отменить открытие или сохранить отчёт.",
                style = MaterialTheme.typography.bodySmall)
            if (slow || state.failure != null) {
                Text("Прошло ${elapsed / 1000} с", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { saveReport(state) }) { Text("Сохранить технический отчёт") }
            }
            if (state.failure != null && onRetry != null) TextButton(onClick = onRetry) { Text("Повторить") }
            if (state.failure != null && onScroll != null) TextButton(onClick = onScroll) { Text("Открыть прокруткой") }
            TextButton(onClick = onCancel) { Text("В библиотеку") }
        }
    }
}
