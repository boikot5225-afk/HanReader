package com.hanreader.app.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.hanreader.app.zh.isHan
import kotlinx.coroutines.launch

internal const val AI_LIMIT_MESSAGE = "Достигнут дневной лимит запросов DeepSeek. Его можно изменить в настройках."
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
    limitReached -> AI_LIMIT_MESSAGE
    automatic -> "Это слово ещё не проверено."
    else -> "Автопроверка выключена."
}

/** «Уточнить» is for a word or a short construction; a longer fragment is explained instead. */
internal fun aiRefinable(text: String): Boolean {
    val han = text.count { it.isHan() }
    return if (han > 0) han <= 8 else text.trim().split(Regex("\\s+")).size <= 5
}

@Composable
internal fun AiWordControls(row: AiCheck?, enabled: Boolean, busy: Boolean, limitReached: Boolean, manual: Boolean,
    onCheck: () -> Unit, onState: (AiCheck, String) -> Unit, status: String? = null, refinable: Boolean = true) {
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
        if (!manual && enabled && !refinable) Text("Уточнение — для слова или короткого выражения. Длинный фрагмент лучше объяснить целиком.",
            style = MaterialTheme.typography.bodySmall)
        else if (!manual && enabled) TextButton(onClick = onCheck, enabled = !busy && (!limitReached || row != null)) { Text("Уточнить с DeepSeek") }
        else if (!manual) TextButton(onClick = { settings = true }) { Text("Настроить DeepSeek") }
    }
    if (settings) AiSettingsDialog { settings = false }
}

/**
 * «Объясни с ИИ» for [text] (a selected phrase or the sentence of a word): a saved explanation
 * shows at once; a new one is a paid request only after the reader asks for it.
 */
@Composable
internal fun AiExplainPanel(language: String, variant: String, text: String, paragraph: String,
    focus: String? = null, label: String = "Объяснить с DeepSeek", contextAi: ContextAi? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ai = contextAi ?: remember(context) { ContextAi.get(context) }
    val c by ai.settings.config.collectAsState()
    val usage by ai.repository.usage.collectAsState()
    val scope = rememberCoroutineScope()
    val payload = remember(language, variant, text, paragraph, focus) { AiExplain.payload(language, variant, text, paragraph, focus) }
    var result by remember(payload) { mutableStateOf<AiExplanation?>(null) }
    var error by remember(payload) { mutableStateOf<String?>(null) }
    var loading by remember(payload) { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val enabled = c.enabled && c.consent && c.hasKey
    LaunchedEffect(payload, c.model) { if (payload != null) result = ai.repository.savedExplanation(payload) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val r = result
        when {
            r != null -> AiExplanationView(r)
            payload == null -> Text("Фрагмент слишком длинный для объяснения (более ${AiExplain.MAX_TEXT} символов).",
                style = MaterialTheme.typography.bodySmall)
            !enabled -> TextButton(onClick = { settings = true }) { Text("Объяснение с ИИ: настроить DeepSeek") }
            loading -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(androidx.compose.ui.Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("DeepSeek объясняет…", androidx.compose.ui.Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                val limited = usage.requests >= c.limit
                OutlinedButton(enabled = !limited, onClick = {
                    loading = true; error = null
                    scope.launch {
                        try { result = ai.repository.explain(payload) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { error = (e as? AiFailure)?.userMessage ?: "Не удалось получить объяснение." }
                        finally { loading = false }
                    }
                }) { Text(label) }
                (error ?: if (limited) AI_LIMIT_MESSAGE else null)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (settings) AiSettingsDialog { settings = false }
}

@Composable
private fun AiExplanationView(e: AiExplanation) {
    Text(e.translation, style = MaterialTheme.typography.bodyLarge)
    for (p in e.parts) Column {
        Text(p.text + (p.reading?.let { "  $it" } ?: ""), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Text(p.meaning, style = MaterialTheme.typography.bodyMedium)
    }
    e.grammar?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Text("Объяснение DeepSeek — может ошибаться.", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
