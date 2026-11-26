package com.hanreader.app.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hanreader.app.LexiconDatabase
import com.hanreader.app.lang.LanguageModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow

/** Geometry is transient. Only source offsets are ever saved. Includes scroll and pager prefetch. */
internal class EncounterViewport {
    var viewport by mutableStateOf(Rect.Zero)
    val bounds = mutableStateMapOf<String, Pair<Int, Rect>>()
    val lines = mutableStateMapOf<String, List<ReaderLineRange>>()
    fun visible(): List<WikiVisibleSpan> = bounds.mapNotNull { (key, source) ->
        val (block, rect) = source
        if (!rect.overlaps(viewport)) null else {
            val shown = lines[key].orEmpty().filter {
                val center = rect.top + (it.top + it.bottom) / 2
                center >= viewport.top && center <= viewport.bottom
            }
            if (shown.isEmpty()) null else WikiVisibleSpan(block, shown.minOf { it.start }, shown.maxOf { it.end })
        }
    }.groupBy { it.block }.map { (block, spans) ->
        WikiVisibleSpan(block, spans.minOf { it.start }, spans.maxOf { it.end })
    }.sortedBy { it.block }
}

private val historyError = MutableStateFlow<String?>(null)

@Composable
internal fun historyEnabled(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("word-history", android.content.Context.MODE_PRIVATE) }
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean("enabled", true)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> enabled = prefs.getBoolean("enabled", true) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

@Composable
internal fun TrackWordEncounters(source: EncounterSource, chapter: ChapterContent?, engine: LanguageModule?,
    visible: List<WikiVisibleSpan>, active: Boolean, daoOverride: WordHistoryDao? = null) {
    val context = LocalContext.current
    val enabled = historyEnabled()
    val session = remember(source, chapter, engine) { if (chapter != null && engine != null) WordHistorySession(source, chapter, engine) else null }
    LaunchedEffect(source, chapter, engine, visible, active, enabled) {
        if (!enabled || !active || chapter == null || engine == null || visible.isEmpty()) return@LaunchedEffect
        delay(700) // Passing pages and scroll flings do not count as reading.
        try {
            withContext(Dispatchers.Default) {
                session?.record(visible, daoOverride ?: LexiconDatabase.get(context).wordHistoryDao())
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { historyError.value = "Не удалось сохранить историю. Проверь свободное место." }
    }
}

@Composable
internal fun RecordWordLookup(source: EncounterSource, chapter: ChapterContent, engine: LanguageModule,
    span: WikiVisibleSpan, daoOverride: WordHistoryDao? = null) {
    val context = LocalContext.current
    val enabled = historyEnabled()
    LaunchedEffect(source, chapter, engine, span, enabled) {
        if (!enabled) return@LaunchedEffect
        try {
            withContext(Dispatchers.Default) {
                (daoOverride ?: LexiconDatabase.get(context).wordHistoryDao()).record(
                    WordHistory.batch(source, chapter, listOf(span), engine, selection = true), lookup = true)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { historyError.value = "Не удалось сохранить историю. Проверь свободное место." }
    }
}

@Composable
internal fun WordHistoryPanel(language: String, lemma: String, daoOverride: WordHistoryDao? = null) {
    val context = LocalContext.current
    var open by remember(language, lemma) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var confirm by remember(language, lemma) { mutableStateOf(false) }
    val enabled = historyEnabled()
    val error by historyError.collectAsState()
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) "Скрыть историю слова" else "История слова")
    }
    if (open) {
        val dao = remember(context, daoOverride) { daoOverride ?: LexiconDatabase.get(context).wordHistoryDao() }
        val statsFlow = remember(dao, language, lemma) { dao.stats(language, lemma).catch { historyError.value = "Не удалось прочитать историю слова." } }
        val rowsFlow = remember(dao, language, lemma) { dao.contexts(language, lemma).catch { historyError.value = "Не удалось прочитать историю слова." } }
        val stats by statsFlow.collectAsState(initial = null)
        val rows by rowsFlow.collectAsState(initial = emptyList())
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(lemma, style = MaterialTheme.typography.titleSmall)
            stats?.let { Text("Встреч: ${it.encounters} · обращений к словарю: ${it.lookups} · источников: ${it.sources}",
                style = MaterialTheme.typography.bodySmall) }
            Text("Встреча — уникальное место в прочитанном фрагменте. Возврат назад не увеличивает число встреч. История хранится в базе приложения и входит в резервную ZIP-копию вместе с библиотекой.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Сохранять историю чтения", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = enabled, onCheckedChange = { on ->
                    context.getSharedPreferences("word-history", android.content.Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
                })
            }
            rows.forEach { row ->
                HorizontalDivider()
                Text(listOf(row.title, row.chapterTitle).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall)
                Text(remember(row.firstSeen) { java.text.DateFormat.getDateInstance().format(java.util.Date(row.firstSeen)) },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val sentence = remember(row) {
                    val start = (row.start - row.textStart).coerceIn(0, row.text.length)
                    val end = (row.end - row.textStart).coerceIn(start, row.text.length)
                    ChapterBuilder.sentence(row.text, start, end)
                }
                Text(sentence, style = MaterialTheme.typography.bodySmall)
            }
            if (stats?.encounters == 0) Text("Сохранённых встреч пока нет.", style = MaterialTheme.typography.bodySmall)
            if ((stats?.encounters ?: 0) > rows.size) Text("Последние ${rows.size} встреч", style = MaterialTheme.typography.labelSmall)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { confirm = true }) { Text("Удалить историю этого слова") }
        }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Удалить историю слова?") },
            text = { Text("Контексты и счётчики «$lemma» будут удалены. Карточки и отметки «Знаю» сохранятся.") },
            confirmButton = { TextButton(onClick = {
                confirm = false
                scope.launch { try { dao.clearWord(language, lemma) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { historyError.value = "Не удалось удалить историю слова." } }
            }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
    }
}
