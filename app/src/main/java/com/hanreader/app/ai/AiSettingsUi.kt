package com.hanreader.app.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiSettingsSection() {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }) { Text("Контекстный ИИ · DeepSeek") }
    if (show) AiSettingsDialog { show = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val ai = remember(context) { ContextAi.get(context) }
    val c by ai.settings.config.collectAsState()
    val usage by ai.repository.usage.collectAsState()
    val busy by ai.repository.busy.collectAsState()
    val appearance by com.hanreader.app.AppearanceStore.appearance.collectAsState()
    val message by ai.repository.message.collectAsState()
    val scope = rememberCoroutineScope()
    // Never save this field in savedInstanceState or copy a stored key back into the UI.
    var secret by remember { mutableStateOf("") }
    var model by remember(c.model) { mutableStateOf(c.model) }
    var limit by remember(c.limit) { mutableStateOf(c.limit.toString()) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    fun action(work: suspend () -> Unit) {
        scope.launch {
            saving = true
            try { work() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { localMessage = "Не удалось сохранить настройки или ключ. Попробуй ещё раз." }
            finally { saving = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Контекстный ИИ", style = MaterialTheme.typography.titleLarge)
            Text("Проверка испанских, английских и китайских подсказок на русском языке. Фрагменты книг и статей Википедии будут переданы DeepSeek. Запросы, включая проверку подключения, оплачиваются с твоего личного API-баланса.")
            Text("Ключ, выбранный режим и лимит запросов общие для всех трёх языков.", style = MaterialTheme.typography.bodySmall)
            Row {
                Checkbox(c.consent, onCheckedChange = { ai.settings.update(c.copy(consent = it, enabled = c.enabled && it)) })
                Text("Согласен передавать фрагменты и оплачивать запросы", Modifier.weight(1f))
            }
            OutlinedTextField(secret, { secret = it }, label = { Text(if (c.hasKey) "Новый API-ключ (ключ сохранён)" else "API-ключ DeepSeek") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(enabled = !saving && secret.isNotBlank(), onClick = {
                    val value = secret.trim(); secret = ""
                    ai.settings.update(c.copy(enabled = false))
                    action {
                        withContext(Dispatchers.IO) { ai.settings.vault.save(value) }
                        ai.settings.refresh(); localMessage = "Ключ зашифрован и сохранён на устройстве."
                    }
                }) { Text("Сохранить ключ") }
                TextButton(enabled = !saving && c.hasKey, onClick = {
                    secret = ""; ai.settings.update(c.copy(enabled = false, automatic = false))
                    action { withContext(Dispatchers.IO) { ai.settings.vault.delete() }; ai.settings.refresh(); localMessage = "Ключ удалён." }
                }) { Text("Удалить ключ") }
            }
            Row {
                Switch(c.enabled, onCheckedChange = { ai.settings.update(c.copy(enabled = it)) }, enabled = c.consent && c.hasKey)
                Text("Включить ИИ", Modifier.padding(12.dp))
            }
            Text("Режим")
            Row {
                FilterChip(!c.automatic, { ai.settings.update(c.copy(automatic = false)) }, label = { Text("По нажатию") })
                Spacer(Modifier.width(8.dp))
                FilterChip(c.automatic, { ai.settings.update(c.copy(automatic = true)) }, label = { Text("Автоматически") })
            }
            Text("В книгах проверяются видимые незнакомые слова, даже если переводы над строками выключены. В запрос может войти до четырёх слов без словарного значения. В Википедии проверяются видимые подсказки. Уверенные исправления применяются в этом месте, сомнительные можно принять вручную.", style = MaterialTheme.typography.bodySmall)
            if (appearance.gloss == com.hanreader.app.GlossMode.OFF) {
                TextButton(onClick = { com.hanreader.app.AppearanceStore.update { it.copy(gloss = com.hanreader.app.GlossMode.UNKNOWN) } }) { Text("Показать переводы над незнакомыми словами") }
            }
            OutlinedTextField(model, { model = it }, label = { Text("Модель") }, singleLine = true)
            OutlinedTextField(limit, { limit = it }, label = { Text("Лимит запросов за сеанс (1–200)") }, singleLine = true)
            TextButton(enabled = model.matches(Regex("[a-zA-Z0-9._-]{1,80}")) && (limit.toIntOrNull() ?: 0) in 1..200,
                onClick = { ai.settings.update(c.copy(model = model, limit = limit.toInt())); localMessage = "Настройки сохранены." }) { Text("Сохранить модель и лимит") }
            Row {
                Switch(c.indicators, onCheckedChange = { ai.settings.update(c.copy(indicators = it)) })
                Text("Отмечать исправления точкой", Modifier.padding(12.dp))
            }
            Text("За сеанс: запросов ${usage.requests}/${c.limit}, токенов ${usage.tokens}" +
                if (usage.unknown > 0) ". Расход ещё ${usage.unknown} запросов неизвестен." else ".")
            Text("Сеанс длится до завершения процесса приложения. Смена книги или статьи не сбрасывает лимит.", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = c.enabled && c.hasKey && c.consent && !busy && !saving,
                onClick = { localMessage = null; action { ai.repository.probe() } }) { Text(if (busy) "Проверяю…" else "Проверить подключение (платный запрос)") }
            (localMessage ?: message)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            TextButton(enabled = !busy && !saving, onClick = { action { ai.repository.clear(false); localMessage = "Кеш проверок очищен. Принятые исправления и отказы сохранены." } }) { Text("Очистить кеш проверок") }
            TextButton(enabled = !busy && !saving, onClick = { confirmClear = true }) { Text("Удалить все результаты ИИ…") }
            Text("Исправления книг сохраняются в резервной копии вместе с книгами. Статьи Википедии и их исправления пока хранятся только на этом устройстве. После восстановления ключ DeepSeek нужно ввести заново.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClose) { Text("Готово") }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Удалить все результаты ИИ?") },
        text = { Text("Удалятся исправления, предложения и отказы. Ручные значения останутся. Повторная проверка потребует запросов.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; action { ai.repository.clear(true); localMessage = "Результаты ИИ удалены." } }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } })
}
