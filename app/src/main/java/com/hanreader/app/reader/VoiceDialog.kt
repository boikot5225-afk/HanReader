package com.hanreader.app.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hanreader.app.AudioPlayer
import com.hanreader.app.NeuralVoice

private const val SAMPLE = "你好！我们一起读中文书吧。"

/** Choice of the voice for reading aloud: the phone's or the server's neural voice, and which one. */
@Composable
fun VoiceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val voice = remember { NeuralVoice.get(context) }
    var neural by remember { mutableStateOf(voice.enabled) }
    var chosen by remember { mutableStateOf(voice.voice) }
    var url by remember { mutableStateOf(voice.serverUrl) }
    var token by remember { mutableStateOf(voice.token) }
    var showServer by remember { mutableStateOf(voice.serverUrl.isBlank()) }
    var status by remember { mutableStateOf("") }
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    fun save() = voice.update(neural, chosen, url, token)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Голос для чтения") },
        text = {
            VoiceChoices(neural, { neural = it }, chosen, { chosen = it }, url, { url = it }, token, { token = it },
                showServer, { showServer = it }, status, onListen = {
                    save()
                    status = "Загрузка…"
                    player.stop()
                    voice.fetch(SAMPLE, 1.0f) { r ->
                        r.fold(
                            onSuccess = { f -> status = ""; player.play(f, onDone = {}, onError = { status = "Не удалось воспроизвести" }) },
                            onFailure = { e -> status = "Сервер не ответил: ${e.message ?: "ошибка сети"}" }
                        )
                    }
                })
        },
        confirmButton = { TextButton(onClick = { save(); onDismiss() }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/** The dialog's content: phone or neural voice, the voice, the server. */
@Composable
internal fun VoiceChoices(
    neural: Boolean, onNeural: (Boolean) -> Unit,
    chosen: String, onChosen: (String) -> Unit,
    url: String, onUrl: (String) -> Unit,
    token: String, onToken: (String) -> Unit,
    showServer: Boolean, onShowServer: (Boolean) -> Unit,
    status: String, onListen: () -> Unit
) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Choice("Голос телефона", "Синтез речи Android, без интернета", !neural) { onNeural(false) }
        Choice("Нейросетевой голос", "Единый живой голос; нужен интернет", neural) { onNeural(true) }
        if (neural) {
            Text("Голос", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            NeuralVoice.VOICES.forEach { (id, name) -> Choice(name, null, chosen == id) { onChosen(id) } }
            TextButton(onClick = onListen, enabled = url.isNotBlank()) { Text("Послушать") }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(
                "Для озвучки текст предложения отправляется на сервер HanReader. Больше ничего не передаётся; " +
                    "готовые записи хранятся на телефоне и повторно не скачиваются.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { onShowServer(!showServer) }) { Text(if (showServer) "Скрыть адрес сервера" else "Адрес сервера") }
            if (showServer) {
                OutlinedTextField(
                    value = url, onValueChange = onUrl, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Адрес сервера") }, placeholder = { Text("https://…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = token, onValueChange = onToken, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ключ доступа (если есть)") }, visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
    }
}

@Composable
private fun Choice(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

