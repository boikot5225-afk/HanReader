package com.hanreader.app.reader

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** All languages use the installed Android TTS engine. No app-operated speech server. */
@Composable
fun VoiceDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Голос для чтения") },
        text = { Text("Читает синтезатор речи Android. Нужный голос можно установить в настройках телефона. Для чтения без интернета скачайте голос, который работает на устройстве. Использование сети зависит от выбранного синтезатора.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } }
    )
}
