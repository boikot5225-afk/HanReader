package com.hanreader.app.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    canUnlink: Boolean = true,
    onUseSaved: ((Long, String, (Result<Unit>) -> Unit) -> Unit)? = null
) {
    var choosing by remember(spelling) { mutableStateOf(false) }
    var editing by remember(current?.id) { mutableStateOf(false) }
    var alias by remember(current?.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember(spelling) { mutableStateOf<String?>(null) }
    val wide = remember(entries) { entries.filter { it.chapterIndex == Highlights.BOOK_WIDE } }
    val owner = current?.let { EntityLinks.root(it, wide) }
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
                wide.filter { it != owner && EntityLinks.root(it, wide) == owner && it.note.isNotBlank() }.forEach { original ->
                    Text("Сохранённая заметка для «${original.text}»", style = MaterialTheme.typography.labelMedium)
                    Text(original.note, style = MaterialTheme.typography.bodySmall)
                }
                val variants = EntityLinks.variants(owner, wide)
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
        if (owner == null) TextButton(enabled = !busy, onClick = { busy = true; onLink(spelling, null, done) }) { Text("Создать общую запись для этого названия") }
        TextButton(enabled = !busy, onClick = { choosing = true }) { Text("Это тот же человек или термин…") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (choosing) androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) choosing = false }) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 600.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("С чем связать «${spelling.take(70)}»?", style = MaterialTheme.typography.titleLarge)
                Text("Выбранные варианты будут открывать общую заметку во всей книге. Существующие заметки сохранятся.", style = MaterialTheme.typography.bodySmall)
                val choices = remember(entries, onUseSaved != null) { EntityLinks.choices(entries).filter { !it.local || onUseSaved != null } }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(choices, key = { it.record.id }) { choice ->
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            if (choice.local) onUseSaved?.invoke(choice.record.id, spelling, done)
                            else onLink(spelling, choice.record.id, done)
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(choice.record.text)
                                if (choice.variants.isNotEmpty()) Text(choice.variants.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                                if (choice.local) Text("Сохранённое выделение · станет общей записью", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (choices.isEmpty()) Text("Сохранённое название не найдено. Выдели полное имя в тексте и создай для него общую запись.", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { busy = true; onLink(spelling, null, done) }) { Text("Создать общую запись для этого названия") }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(enabled = !busy, onClick = { choosing = false }) { Text("Закрыть") }
            }
        }
    }
}
