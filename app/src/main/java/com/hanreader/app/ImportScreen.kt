package com.hanreader.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val AI_PROMPT =
    "Создай карточки для изучения слов. Каждая строка содержит ровно пять полей: " +
        "слово | транскрипция | перевод | предложение с этим словом | перевод предложения. " +
        "Без заголовков, Markdown, нумерации и вертикальных черт внутри полей."

@Suppress("DEPRECATION")
@Composable
fun ImportScreen(language: String, vm: LexiconViewModel, padding: PaddingValues) {
    var text by rememberSaveable(language) { mutableStateOf("") }
    var result by rememberSaveable(language) { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val preview = remember(text) { CardImportParser.parse(text) }
    val clipboard = LocalClipboardManager.current
    val languageName = languages.firstOrNull { it.tag == language }?.title ?: language

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Импорт", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Попроси ИИ создать карточки и вставь ответ сюда. Язык: $languageName.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Overline("Формат строки")
                    Text(
                        "слово | транскрипция | перевод | пример | перевод примера",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Транскрипцию можно оставить пустой. Чтобы добавить контекст к слову, повтори слово и перевод в строке с другим примером.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SmallAction(
                        icon = if (copied) LexiconIcons.Check else LexiconIcons.Copy,
                        label = if (copied) "Промпт скопирован" else "Скопировать промпт для ИИ"
                    ) {
                        clipboard.setText(AnnotatedString("Изучаемый язык: $languageName. $AI_PROMPT"))
                        copied = true
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; result = "" },
                label = { Text("Карточки от ИИ") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 12,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallAction(LexiconIcons.Paste, "Вставить из буфера") {
                    val pasted = clipboard.getText()?.text.orEmpty()
                    if (pasted.isNotBlank()) {
                        text = if (text.isBlank()) pasted else text.trimEnd() + "\n" + pasted
                        result = ""
                    }
                }
                if (text.isNotEmpty()) {
                    SmallAction(LexiconIcons.Close, "Очистить") {
                        text = ""
                        result = ""
                    }
                }
            }
        }
        item {
            Text(
                "Карточек: " + preview.cards.size + " · Ошибок: " + preview.problems.size,
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            BigButton(
                if (saving) "Сохранение…" else "Импортировать " + preview.cards.size,
                enabled = !saving && preview.cards.isNotEmpty(),
                onClick = {
                    saving = true
                    vm.import(preview.cards) { outcome ->
                        saving = false
                        result = outcome.fold(
                            onSuccess = {
                                "Новых слов: " + it.added +
                                    " · Примеров: " + it.examplesAdded +
                                    " · Повторов: " + it.skipped
                            },
                            onFailure = { "Ошибка сохранения: " + (it.message ?: "неизвестна") }
                        )
                    }
                }
            )
        }
        if (result.isNotEmpty()) {
            item {
                Text(
                    result,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (preview.problems.isNotEmpty()) {
            item { Overline("Ошибки строк", Modifier.padding(start = 8.dp, top = 8.dp), color = MaterialTheme.colorScheme.error) }
            items(preview.problems) { problem ->
                Text(
                    "Строка " + problem.line + ": " + problem.message,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (preview.cards.isNotEmpty()) {
            item { Overline("Предпросмотр", Modifier.padding(start = 8.dp, top = 8.dp)) }
            items(preview.cards.take(20)) { card ->
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(card.word, fontFamily = WordFont, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(10.dp))
                            Text(card.translation, modifier = Modifier.weight(1f))
                        }
                        Text(
                            card.example,
                            fontFamily = WordFont,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (preview.cards.size > 20) {
                item {
                    Text(
                        "Показаны первые 20 карточек",
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 14.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
