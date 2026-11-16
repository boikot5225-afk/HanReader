package com.hanreader.app.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

@Composable
fun AiWordControls(row: AiCheck?, enabled: Boolean, busy: Boolean, message: String?, manual: Boolean,
                   onCheck: () -> Unit, onState: (AiCheck, String) -> Unit) {
    var settings by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (row != null) {
            Text("DeepSeek · ${row.original}", style = MaterialTheme.typography.labelMedium)
            Text("Исходная подсказка: ${row.base}", style = MaterialTheme.typography.bodySmall)
            row.translation?.let { Text((if (row.state == "accepted" && !manual) "В этом контексте: " else "Предложение: ") + it) }
            row.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (row.state == "checked") Text("Проверено: исходное значение сохранено.", style = MaterialTheme.typography.bodySmall)
            if (row.state == "rejected") Text("Исправление отклонено; повторно автоматически не применяется.", style = MaterialTheme.typography.bodySmall)
            if (row.state == "proposed" && !manual) TextButton(onClick = { onState(row, "accepted") }) { Text("Применить здесь") }
            if (row.state in setOf("accepted", "proposed")) TextButton(onClick = { onState(row, "rejected") }) { Text("Отменить исправление ИИ") }
        }
        if (manual) Text("Используется твоё значение. ИИ его не заменяет.", style = MaterialTheme.typography.bodySmall)
        else if (enabled) TextButton(onClick = onCheck, enabled = !busy) { Text(if (busy) "Проверяю…" else "Уточнить с DeepSeek") }
        else TextButton(onClick = { settings = true }) { Text("Настроить DeepSeek") }
        if (enabled && message != null) Text(message, style = MaterialTheme.typography.bodySmall)
    }
    if (settings) AiSettingsDialog { settings = false }
}
