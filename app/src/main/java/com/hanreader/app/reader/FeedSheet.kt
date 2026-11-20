package com.hanreader.app.reader

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hanreader.app.feed.*
import com.hanreader.app.lang.Languages
import com.hanreader.app.wiki.WikiDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.text.DateFormat
import java.util.Date

private fun languageName(tag: String) = when (tag) {
    "es" -> "Испанский"; "en" -> "Английский"; "zh" -> "Китайский"; else -> tag
}

private fun isMetered(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
}.getOrDefault(false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedSheet(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val feed = androidx.compose.runtime.remember { FeedRepository(context.applicationContext) }
    val engine by vm.engine.collectAsState()
    val known by vm.knownWords.collectAsState()
    val meanings by vm.userMeanings.collectAsState()
    val studying by vm.studyingWords.collectAsState()
    val levelMark by vm.levelMark.collectAsState()
    val pinyinMode by vm.pinyinMode.collectAsState()
    var settings by remember { mutableStateOf(feed.settings(Languages.current.tag)) }
    var feedItems by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var read by remember { mutableStateOf(feed.readIds()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<WikiDocument?>(null) }
    var attribution by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh(force: Boolean) { scope.launch {
        feedItems = try { feed.cached(settings) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        if (settings.wifiOnly && isMetered(context)) {
            message = if (force) "Включён режим «Только по Wi-Fi»: подключись к Wi-Fi или выключи его" else null
            return@launch
        }
        busy = true; message = null
        try {
            val result = withTimeout(60_000) { feed.refresh(settings) }
            feedItems = feed.cached(settings)
            var fallbackUsed = false
            var failed = result.failed
            if (feedItems.isEmpty()) {
                val fallback = settings.copy(languages = FeedCatalog.languages().toSet())
                if (fallback.languages != settings.languages) {
                    val fallbackResult = withTimeout(60_000) { feed.refresh(fallback) }
                    failed = (failed + fallbackResult.failed).distinctBy { it.id }
                    feedItems = feed.cached(fallback).filter { it.topic in settings.topics }
                    fallbackUsed = feedItems.isNotEmpty()
                }
            }
            message = when {
                fallbackUsed && failed.isNotEmpty() ->
                    "По выбранному языку пусто: показаны доступные материалы на других языках. Не ответили: ${failed.joinToString { it.name }}"
                fallbackUsed ->
                    "По выбранному языку пусто: показаны доступные материалы на других языках."
                result.attempted > 0 && result.failed.size == result.attempted ->
                    "Нет сети или источники недоступны. Показана сохранённая лента."
                result.failed.isNotEmpty() ->
                    "Не все источники ответили: ${result.failed.joinToString { it.name }}"
                else -> null
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { message = "Лента обновляется слишком долго"
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { message = "Не удалось обновить ленту" }
        finally { busy = false }
    } }
    LaunchedEffect(Unit) { refresh(false) }

    fun update(new: FeedSettings) {
        settings = new; feed.save(new)
        scope.launch { feedItems = feed.cached(new) }
    }
    fun open(item: FeedItem) {
        feed.markRead(item.id); read = feed.readIds()
        if (item.opensInReader) scope.launch {
            busy = true; message = null
            try {
                reading = withTimeout(20_000) { vm.wikipedia.fullArticle(vm.wikipedia.article(item.language, item.wikiPageId)) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) { message = "Не удалось открыть статью вовремя"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { message = "Не удалось открыть статью. Нужен интернет." }
            finally { busy = false }
        } else runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { message = "Не нашлось приложения для открытия ссылки" }
    }

    reading?.let { document ->
        WikipediaReader(document, vm.wikipedia, engine, known, onDismiss = { reading = null }, meanings = meanings, onKnown = vm::setKnown,
            studying = studying, levelMark = levelMark, pinyinMode = pinyinMode, onPinyinMode = vm::setPinyinMode)
        return
    }

    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Лента", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { refresh(true) }) { Text("Обновить") }
            }
            Text("Статьи по языкам и темам. Статьи Википедии читаются со словарными подсказками, остальные новости открываются в браузере.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedCatalog.languages().forEach { tag ->
                    FilterChip(selected = tag in settings.languages, label = { Text(languageName(tag)) }, onClick = {
                        val next = if (tag in settings.languages) settings.languages - tag else settings.languages + tag
                        if (next.isNotEmpty()) update(settings.copy(languages = next))
                    })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedCatalog.topicsFor(settings.languages).forEach { topic ->
                    FilterChip(selected = topic in settings.topics, label = { Text(topic.title) }, onClick = {
                        val next = if (topic in settings.topics) settings.topics - topic else settings.topics + topic
                        if (next.isNotEmpty()) update(settings.copy(topics = next))
                    })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Только по Wi-Fi", Modifier.weight(1f))
                Switch(checked = settings.wifiOnly, onCheckedChange = { update(settings.copy(wifiOnly = it)) })
            }
            Row {
                TextButton(onClick = { attribution = !attribution }) { Text(if (attribution) "Скрыть источники" else "Источники и лицензии") }
                TextButton(onClick = { scope.launch { feed.clear(); feedItems = emptyList() } }) { Text("Очистить ленту") }
            }
            if (attribution) FeedCatalog.sources.filter { it.language in settings.languages }.distinctBy { it.name to it.license }.forEach {
                Text("${it.name}: ${it.license}", style = MaterialTheme.typography.bodySmall)
            }
            Text("При обновлении источники видят твой IP-адрес и тип приложения. Книги и слова не отправляются.", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 520.dp)) {
            if (feedItems.isEmpty() && !busy) item { Text("Пока пусто. Нажми «Обновить» при наличии интернета или выбери другие язык и темы.", Modifier.padding(24.dp)) }
            items(feedItems, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium,
                        color = if (item.id in read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text("${item.sourceName} · ${item.topic.title} · ${dateFormat.format(Date(item.publishedAt))}" +
                        if (item.id in read) " · прочитано" else "", style = MaterialTheme.typography.bodySmall)
                    if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                    TextButton(enabled = !busy, onClick = { open(item) }) { Text(if (item.opensInReader) "Читать в приложении" else "Открыть в браузере") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
