package com.hanreader.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext

/** Mounted only after revealing/grading, in both recognition and writing. No answer leaks. */
@Composable
internal fun ReadingExamplesPanel(card: WordCard, examples: List<CardExample>, vm: LexiconViewModel) {
    var failed by remember(card.id) { mutableStateOf(false) }
    val flow = remember(card.id, card.word, card.language) {
        vm.readingContexts(card).catch { failed = true; emit(emptyList()) }
    }
    val rows by flow.collectAsState(initial = emptyList())
    val suggestions by produceState(emptyList<ReadingExample>(), card, rows, examples) {
        value = withContext(Dispatchers.Default) { ReadingExamples.suggestions(card, rows, examples) }
    }
    if (failed) Text("История сейчас недоступна. Можно повторять сохранённые примеры.", style = MaterialTheme.typography.bodySmall)
    ReadingExamplesChoices(card, suggestions) { id, done -> vm.addReadingExample(card, id, done) }
}

@Composable
internal fun ReadingExamplesChoices(card: WordCard, suggestions: List<ReadingExample>,
    onAdd: (String, (Result<Boolean>) -> Unit) -> Unit) {
    var selected by remember(card.id) { mutableStateOf<String?>(null) }
    var added by remember(card.id) { mutableStateOf(emptySet<String>()) }
    var busy by remember(card.id) { mutableStateOf(false) }
    var notice by remember(card.id) { mutableStateOf<String?>(null) }
    val candidates = suggestions.filter { it.encounterId !in added }
    val current = candidates.firstOrNull { it.encounterId == selected } ?: candidates.firstOrNull()
    if (current == null && notice == null) return
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Overline("Встречалось в чтении")
            if (current != null) {
                Text(current.sentence, fontFamily = WordFont, style = MaterialTheme.typography.bodyLarge)
                Text(current.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Добавляй примеры, в которых слово означает «${card.translation}».",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (candidates.size > 1) TextButton(enabled = !busy, onClick = {
                        selected = candidates[(candidates.indexOf(current) + 1) % candidates.size].encounterId
                    }) { Text("Ещё пример") }
                    TextButton(enabled = !busy, onClick = {
                        busy = true; notice = null
                        onAdd(current.encounterId) { result ->
                            busy = false
                            result.fold(onSuccess = { fresh ->
                                added = added + current.encounterId
                                notice = if (fresh) "Пример добавлен. Он будет чередоваться с другими при повторении." else "Этот пример уже есть в карточке."
                            }, onFailure = { notice = "Не удалось добавить пример. Возможно, карточка или контекст уже удалены." })
                        }
                    }) { Text(if (busy) "Добавляю…" else "В примеры карточки") }
                }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
