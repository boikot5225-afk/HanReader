package com.hanreader.app.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

internal const val AI_UNCHANGED_MESSAGE = "Проверено: перевод сохранён без изменений."
internal const val AI_UNKNOWN_CHECKED_MESSAGE = "Проверено: надёжного значения для неизвестного слова не найдено."

@Composable
fun AiWordControls(row: AiCheck?, enabled: Boolean, busy: Boolean, message: String?, manual: Boolean,
                   onCheck: () -> Unit, onState: (AiCheck, String) -> Unit) {
    var settings by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (row != null) {
            Text("DeepSeek · ${row.original}", style = MaterialTheme.typography.labelMedium)
            Text(if (row.base.isBlank()) "Исходное значение: в словаре отсутствует" else "Исходная подсказка: ${row.base}", style = MaterialTheme.typography.bodySmall)
            row.translation?.let { Text((if (row.state == "accepted" && !manual) "В этом контексте: " else "Предложение: ") + it) }
            row.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (row.state == "checked") Text(
                if (row.base.isBlank()) AI_UNKNOWN_CHECKED_MESSAGE else AI_UNCHANGED_MESSAGE,
                style = MaterialTheme.typography.bodySmall
            )
            if (row.state == "rejected") Text("Исправление отклонено; повторно автоматически не применяется.", style = MaterialTheme.typography.bodySmall)
            if (row.state == "proposed" && !manual) TextButton(onClick = { onState(row, "accepted") }) { Text("Применить здесь") }
            if (row.state in setOf("accepted", "proposed")) TextButton(onClick = { onState(row, "rejected") }) { Text("Отменить исправление ИИ") }
        }
        if (manual) Text("Используется твоё значение. ИИ его не заменяет.", style = MaterialTheme.typography.bodySmall)
        else if (enabled) TextButton(onClick = onCheck, enabled = !busy) { Text(if (busy) "Проверяю…" else "Уточнить с DeepSeek") }
        else TextButton(onClick = { settings = true }) { Text("Настроить DeepSeek") }
        val repeatsCheck = row?.state == "checked" &&
            message == if (row.base.isBlank()) AI_UNKNOWN_CHECKED_MESSAGE else AI_UNCHANGED_MESSAGE
        if (enabled && message != null && !repeatsCheck) Text(message, style = MaterialTheme.typography.bodySmall)
    }
    if (settings) AiSettingsDialog { settings = false }
}
