package com.hanreader.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export and restore the whole database through files the user picks. */
@Composable
fun BackupSection(vm: LexiconViewModel) {
    var status by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Backup?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            busy = true
            status = "Сохранение…"
            failed = false
            vm.exportBackup(uri) { result ->
                busy = false
                result.fold(
                    onSuccess = {
                        failed = false
                        status = "Копия сохранена: $it " + RussianText.plural(it, "слово", "слова", "слов") + "."
                    },
                    onFailure = {
                        failed = true
                        status = "Не удалось сохранить копию: " + (it.message ?: "ошибка записи")
                    }
                )
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            status = ""
            vm.readBackup(uri) { result ->
                busy = false
                result.fold(
                    onSuccess = { pending = it },
                    onFailure = {
                        failed = true
                        status = it.message ?: "Не удалось прочитать файл."
                    }
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Overline("Резервная копия")
        Text(
            "Все языки: слова, примеры, прогресс и настройки. Сохраняй копию перед переустановкой или сменой телефона.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        BackupButton(LexiconIcons.Download, "Сохранить копию в файл", enabled = !busy) {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            exportLauncher.launch("hanreader-backup-$day.json")
        }
        BackupButton(LexiconIcons.Paste, "Восстановить из файла", enabled = !busy) {
            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
        }
        if (status.isNotEmpty()) {
            Text(
                status,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    pending?.let { backup ->
        val date = if (backup.exportedAt > 0L) {
            SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru")).format(Date(backup.exportedAt))
        } else "неизвестно"
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Восстановить копию?", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Копия от $date: ${backup.cards.size} " +
                        RussianText.plural(backup.cards.size, "слово", "слова", "слов") +
                        ". Все слова и прогресс, которые сейчас есть в приложении, будут заменены данными из копии."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        vm.restoreBackup(backup) { result ->
                            busy = false
                            pending = null
                            result.fold(
                                onSuccess = {
                                    failed = false
                                    status = "Копия восстановлена: ${backup.cards.size} " +
                                        RussianText.plural(backup.cards.size, "слово", "слова", "слов") + "."
                                },
                                onFailure = {
                                    failed = true
                                    status = "Не удалось восстановить, данные не изменены: " +
                                        (it.message ?: "ошибка базы")
                                }
                            )
                        }
                    }
                ) { Text(if (busy) "Восстановление…" else "Заменить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pending = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun BackupButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
