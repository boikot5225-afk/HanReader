package com.hanreader.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Opens the system share sheet with the reports as text (Telegram, mail...). */
fun shareCrashReports(context: Context) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "HanReader: отчёт о сбое")
        putExtra(Intent.EXTRA_TEXT, CrashLog.text(context).take(200_000))
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Отправить отчёт").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Settings: how many reports are saved; look at them, send them, delete them. */
@Composable
fun CrashReportsSection() {
    val context = LocalContext.current
    var count by remember { mutableIntStateOf(CrashLog.reports(context).size) }
    var showing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Overline("Отчёты о сбоях")
        Text(
            if (count == 0) "Сбоев не было." else "Сохранено отчётов: $count. В них только версия, модель телефона и техническое описание ошибки — без текста книг и слов.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        if (count > 0) {
            Row {
                TextButton(onClick = { showing = true }) { Text("Посмотреть") }
                TextButton(onClick = { shareCrashReports(context) }) { Text("Отправить") }
                TextButton(onClick = { CrashLog.clear(context); count = 0 }) { Text("Удалить") }
            }
        }
    }
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text("Отчёты о сбоях") },
            text = {
                Text(
                    CrashLog.text(context).take(20_000),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = { TextButton(onClick = { showing = false }) { Text("Закрыть") } }
        )
    }
}

/** After a crash: once, on the next start, offer to send the report. */
@Composable
fun CrashPrompt() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(CrashLog.hasCrash(context)) }
    if (!open) return
    fun done(clear: Boolean) {
        open = false
        if (clear) CrashLog.clear(context)
        else CrashLog.reports(context).filter { it.name.startsWith("crash-") }.forEach { f ->
            // Keep the report for settings, but do not ask again about it.
            f.renameTo(java.io.File(f.parentFile, "seen-" + f.name))
        }
    }
    AlertDialog(
        onDismissRequest = { done(clear = false) },
        title = { Text("Приложение закрылось с ошибкой") },
        text = {
            Text("Отправь отчёт разработчику — это поможет найти и исправить ошибку. В отчёте только версия, модель телефона и техническое описание ошибки, без текста книг. Посмотреть его можно в настройках.")
        },
        confirmButton = {
            TextButton(onClick = { shareCrashReports(context); done(clear = false) }) { Text("Отправить") }
        },
        dismissButton = {
            TextButton(onClick = { done(clear = true) }) { Text("Не надо") }
        }
    )
}
