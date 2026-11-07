package com.hanreader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One source the app is built from: what it gives, who made it, its licence, the file with the details. */
internal data class Credit(val what: String, val source: String, val license: String, val file: String)

internal val CREDITS = listOf(
    Credit("Китайско-английский словарь", "CC-CEDICT, MDBG", "CC BY-SA 4.0", "CC-CEDICT_NOTICE.txt"),
    Credit("Русские значения", "Русский Викисловарь (через kaikki.org)", "CC BY-SA 4.0", "RU_WIKTIONARY_NOTICE.txt"),
    Credit("Машинный перевод значений на русский", "производная от CC-CEDICT, перевод DeepSeek", "CC BY-SA 4.0", "RU_AI_NOTICE.txt"),
    Credit("Разбивка текста на слова", "Jieba 0.42.1", "MIT", "JIEBA_NOTICE.txt"),
    Credit("Имена собственные", "разметка словаря Jieba", "MIT", "NAMES_NOTICE.txt"),
    Credit("Чтения пиньинь", "pypinyin 0.53.0", "MIT", "PYPINYIN_NOTICE.txt"),
    Credit("Уровни HSK 3.0 и 2.0", "Complete HSK Vocabulary; hskhsk.com", "MIT", "HSK_LEVELS_NOTICE.txt"),
    Credit("Частоты слов HSK", "Complete HSK Vocabulary", "MIT", "HSK_FREQUENCY_NOTICE.txt"),
    Credit("Упрощённые и традиционные иероглифы", "OpenCC", "Apache 2.0", "OPENCC_NOTICE.txt"),
    Credit("Шрифты", "Noto, LXGW WenKai, Zhuque Fangsong, Inter и др.", "SIL OFL 1.1", "FONTS_NOTICE.txt"),
    Credit("Библиотеки", "Kotlin, AndroidX, jsoup", "Apache 2.0, MIT", "LIBRARIES_NOTICE.txt"),
)

internal val LICENSE_TEXTS = listOf(
    "Apache License 2.0" to "APACHE-2.0.txt",
    "SIL Open Font License 1.1" to "OFL.txt"
)

/** Settings entry: opens «О приложении». */
@Composable
fun AboutSection() {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Overline("О приложении")
        TextButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("Версия, источники данных, лицензии, конфиденциальность")
        }
    }
    if (open) AboutDialog(onClose = { open = false })
}

@Composable
fun AboutDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    Dialog(onDismissRequest = { if (shown != null) shown = null else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (shown != null) shown = null else onClose() }) { Icon(LexiconIcons.Back, contentDescription = "Назад") }
                Text(shown?.first ?: "О приложении", style = MaterialTheme.typography.titleMedium)
            }
            val doc = shown
            if (doc != null) {
                val text = remember(doc) {
                    runCatching { context.assets.open(doc.second).bufferedReader().use { it.readText() } }.getOrDefault("Файл не найден")
                }
                Text(
                    text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    fontFamily = if (doc.second.endsWith(".md")) null else FontFamily.Monospace,
                    fontSize = if (doc.second.endsWith(".md")) 15.sp else 12.sp
                )
                return@Column
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("汉读 HanReader $version", style = MaterialTheme.typography.headlineSmall, fontFamily = WordFont)
                Text(
                    "Читалка китайских книг с пиньинем, словарём и карточками. Работает без интернета и не собирает личные данные.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Связь с разработчиком: boikot5225@gmail.com · Telegram @SainJust",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Исходный код и данные: github.com/boikot5225-afk/HanReader",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { shown = "Политика конфиденциальности" to "privacy_ru.md" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Политика конфиденциальности")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Overline("Источники данных")
                Text(
                    "Словари под лицензией CC BY-SA 4.0 входят в приложение в изменённом виде (сжатый формат, русский перевод) и распространяются под той же лицензией.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CREDITS.forEach { c ->
                    Column(Modifier.fillMaxWidth().clickable { shown = c.what to "licenses/${c.file}" }.padding(vertical = 6.dp)) {
                        Text(c.what, style = MaterialTheme.typography.titleSmall)
                        Text("${c.source} · ${c.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Overline("Тексты лицензий")
                LICENSE_TEXTS.forEach { (name, file) ->
                    TextButton(onClick = { shown = name to "licenses/$file" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(name) }
                }
                Text(
                    "CC BY-SA 4.0: creativecommons.org/licenses/by-sa/4.0 · MIT: условия приведены в файлах источников.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}
