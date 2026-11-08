package com.hanreader.app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hanreader.app.LexiconIcons
import com.hanreader.app.WordFont
import com.hanreader.app.zh.isHan

/**
 * Chinese text to read: pasted here, shared from another app or selected there («Читать в
 * HanReader»). [initial]: the text that came in. [busy]: saving, the buttons wait.
 */
@Composable
fun PasteTextDialog(initial: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (title: String, text: String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(initial) }
    var title by remember { mutableStateOf("") }
    val hasChinese = text.any { it.isHan() }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (!busy) onDismiss() }) { Icon(LexiconIcons.Close, contentDescription = "Закрыть") }
                Text("Текст для чтения", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Название") }, placeholder = { Text(PastedText.title(PastedText.tidy(text)).takeIf { text.isNotBlank() } ?: "Первая строка текста") },
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp),
                    label = { Text("Китайский текст") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = WordFont),
                    shape = RoundedCornerShape(14.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clipboard.getText()?.text?.let { text = if (text.isBlank()) it else text + "\n" + it } }) {
                        Text("Вставить из буфера")
                    }
                    Text(
                        if (text.isBlank()) "" else "${text.length} знаков",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
                if (text.isNotBlank() && !hasChinese) {
                    Text("В тексте нет китайских иероглифов.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = { onSave(title, text) }, enabled = hasChinese && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)
                ) { Text(if (busy) "Сохраняю…" else "Читать") }
                Text(
                    "Текст сохранится в библиотеке как книга: пиньинь, перевод по нажатию, карточки и место чтения — всё как в книгах.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
