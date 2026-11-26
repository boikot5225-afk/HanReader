package com.hanreader.app.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

internal const val AI_UNCHANGED_MESSAGE = "Проверено: перевод сохранён без изменений."
internal const val AI_UNKNOWN_CHECKED_MESSAGE = "Проверено: надёжного значения для неизвестного слова не найдено."

internal fun aiWordStatus(row: AiCheck?, enabled: Boolean, checking: Boolean, message: String?,
    automatic: Boolean, limitReached: Boolean, manual: Boolean): String? = when {
    manual -> "Используется твоё значение. ИИ его не заменяет."
    enabled && checking -> "DeepSeek проверяет это слово…"
    enabled && message != null -> message
    row?.state == "checked" -> if (row.base.isBlank()) AI_UNKNOWN_CHECKED_MESSAGE else AI_UNCHANGED_MESSAGE
    row?.state == "rejected" -> "Исправление отклонено."
    row?.state == "proposed" -> "Есть предложение DeepSeek."
    row?.state == "accepted" -> "Исправление применено здесь."
    !enabled -> null
    limitReached -> "Достигнут лимит запросов за сеанс. Его можно изменить в настройках."
    automatic -> "Это слово ещё не проверено."
    else -> "Автопроверка выключена."
}

@Composable
internal fun AiWordControls(row: AiCheck?, enabled: Boolean, busy: Boolean, limitReached: Boolean, manual: Boolean,
    onCheck: () -> Unit, onState: (AiCheck, String) -> Unit, status: String? = null) {
    var settings by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (row != null) {
            if (row.state in setOf("accepted", "proposed")) {
                Text(if (row.base.isBlank()) "Исходное значение: в словаре отсутствует" else "Исходная подсказка: ${row.base}", style = MaterialTheme.typography.bodySmall)
                row.translation?.let { Text((if (row.state == "accepted" && !manual) "В этом контексте: " else "Предложение: ") + it) }
                row.pinyin?.let {
                    Text(if (row.state == "accepted") "Чтение по контексту: $it" else "Предложенное чтение: $it")
                }
            }
            if (row.state != "rejected") row.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (row.state == "proposed" && !manual) TextButton(onClick = { onState(row, "accepted") }) { Text("Применить здесь") }
            if (row.state in setOf("accepted", "proposed")) TextButton(onClick = { onState(row, "rejected") }) { Text("Отменить исправление ИИ") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!manual && enabled) TextButton(onClick = onCheck, enabled = !busy && (!limitReached || row != null)) { Text("Уточнить с DeepSeek") }
        else if (!manual) TextButton(onClick = { settings = true }) { Text("Настроить DeepSeek") }
    }
    if (settings) AiSettingsDialog { settings = false }
}
