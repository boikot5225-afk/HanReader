package com.hanreader.app.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hanreader.app.Highlight

@Composable
internal fun EntityLinksPanel(
    entries: List<Highlight>, current: Highlight?, spelling: String, automatic: Boolean,
    onLink: (String, Long?, (Result<Unit>) -> Unit) -> Unit,
    onUnlink: (Long, String, (Result<Unit>) -> Unit) -> Unit,
    onChanged: () -> Unit = {},
    canUnlink: Boolean = true
) {
    var choosing by remember(spelling) { mutableStateOf(false) }
    var editing by remember(current?.id) { mutableStateOf(false) }
    var alias by remember(current?.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember(spelling) { mutableStateOf<String?>(null) }
    val owner = current?.let { EntityLinks.root(it, entries) }
    val done: (Result<Unit>) -> Unit = { result ->
        busy = false
        message = result.fold(onSuccess = { choosing = false; alias = ""; onChanged(); "Связь сохранена" },
            onFailure = {
                if (it is IllegalArgumentException) it.message?.takeIf { text -> text.any { c -> c in 'А'..'я' } }
                    ?: "Вариант должен содержать буквы и не больше 200 знаков. В одной записи может быть до 32 вариантов."
                else "Не удалось изменить связь. Попробуй ещё раз."
            })
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (owner != null) {
            Text("Общая запись: ${owner.text}", style = MaterialTheme.typography.labelLarge)
            if (automatic) Text("Связано по полному имени в этом абзаце.", style = MaterialTheme.typography.bodySmall)
            if (canUnlink && EntityLinks.key(spelling) != EntityLinks.key(owner.text)) {
                TextButton(enabled = !busy, onClick = { busy = true; onUnlink(owner.id, spelling, done) }) { Text("Это другое — отменить связь") }
            }
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Скрыть варианты имени" else "Варианты имени и названия") }
            if (editing) {
                entries.filter { it != owner && EntityLinks.root(it, entries) == owner && it.note.isNotBlank() }.forEach { original ->
                    Text("Сохранённая заметка для «${original.text}»", style = MaterialTheme.typography.labelMedium)
                    Text(original.note, style = MaterialTheme.typography.bodySmall)
                }
                val variants = (EntityLinks.aliases(owner) + entries.filter { it != owner && EntityLinks.root(it, entries) == owner }
                    .flatMap { listOf(it.text) + EntityLinks.aliases(it) }).distinctBy(EntityLinks::key)
                variants.forEach { name ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(name, Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(enabled = !busy, onClick = { busy = true; onUnlink(owner.id, name, done) }) { Text("Отвязать") }
                    }
                }
                OutlinedTextField(alias, { if (it.length <= 200) alias = it }, label = { Text("Другой вариант имени или названия") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                TextButton(enabled = !busy && alias.isNotBlank(), onClick = { busy = true; onLink(alias, owner.id, done) }) { Text("Добавить вариант во всей книге") }
                if (EntityLinks.blocked(owner).isNotEmpty()) Text("Автоматически не связывать: ${EntityLinks.blocked(owner).joinToString(", ")}. Для восстановления добавь вариант вручную.", style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(enabled = !busy, onClick = { choosing = true }) { Text("Это тот же человек или термин…") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (choosing) AlertDialog(onDismissRequest = { if (!busy) choosing = false },
        title = { Text("С чем связать «${spelling.take(70)}»?") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            Text("Выбранные варианты будут открывать общую заметку во всей книге. Существующие заметки сохранятся.", style = MaterialTheme.typography.bodySmall)
            EntityLinks.roots(entries).forEach { entry ->
                TextButton(enabled = !busy, onClick = { busy = true; onLink(spelling, entry.id, done) }) { Text(entry.text) }
            }
            TextButton(enabled = !busy, onClick = { busy = true; onLink(spelling, null, done) }) { Text("Создать общую запись для этого названия") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { choosing = false }) { Text("Закрыть") } })
}
