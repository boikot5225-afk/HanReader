package com.hanreader.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hanreader.app.reader.BookStatsScreen
import com.hanreader.app.reader.LibraryScreen
import com.hanreader.app.reader.ReaderScreen
import com.hanreader.app.reader.ReaderViewModel
import kotlinx.coroutines.delay

private enum class Tab(val title: String) {
    READ("Читать"),
    TODAY("Учить"),
    WORDS("Слова"),
    IMPORT("Импорт")
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.READ -> LexiconIcons.Library
    Tab.TODAY -> LexiconIcons.Sun
    Tab.WORDS -> LexiconIcons.Book
    Tab.IMPORT -> LexiconIcons.Download
}

/** Short badge shown in the language picker. */
private fun languageBadge(tag: String): String = when {
    tag.startsWith("zh") -> "中"
    tag.startsWith("ja") -> "日"
    else -> tag.take(2).replaceFirstChar { it.uppercase() }
}

@Composable
fun LexiconScreen(vm: LexiconViewModel = viewModel(), reader: ReaderViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.READ) }
    // An open book takes the whole screen, like a review session.
    var reading by rememberSaveable { mutableStateOf<Long?>(null) }
    var bookStats by rememberSaveable { mutableStateOf<Long?>(null) }
    var readingStats by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reading) { reading?.let { if (reader.open.value?.entity?.id != it) reader.openBook(it) } }
    // A running review session hides the navigation and takes the whole screen.
    var session by rememberSaveable { mutableStateOf<ReviewMode?>(null) }
    // A book sent from another app: import it, then open it.
    val incoming by IncomingBooks.pending.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        IncomingBooks.pending.value = null
        android.widget.Toast.makeText(ctx, "Добавляю книгу…", android.widget.Toast.LENGTH_SHORT).show()
        reader.importBook(uri) { r ->
            r.onSuccess { id -> session = null; bookStats = null; tab = Tab.READ; reading = id }
                .onFailure { e -> android.widget.Toast.makeText(ctx, e.message ?: "Не удалось открыть книгу", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    var wordFilter by rememberSaveable { mutableStateOf(WordFilter.ALL) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val selectedLanguage by vm.language.collectAsState()
    val selectedSettings by vm.learningSettings.collectAsState()
    val settings = if (selectedSettings.language == selectedLanguage) {
        selectedSettings
    } else LearningSettings(selectedLanguage)
    val cards by vm.cards.collectAsState()
    val allExamples by vm.contexts.collectAsState()
    // Avoid showing the previous language's cards during a Flow switch.
    val visibleCards = cards.filter { it.language == selectedLanguage }
    val visibleIds = visibleCards.map { it.id }.toSet()
    val visibleExamples = allExamples.filter { it.cardId in visibleIds }
    val context = LocalContext.current
    val speaker = remember(context) { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.close() } }
    LaunchedEffect(settings.speechRate) {
        speaker.rate = settings.speechRate
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    if (showSettings) LearningSettingsDialog(
        settings = settings, vm = vm, onClose = { showSettings = false }
    )

    CrashPrompt()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The reader handles the system bars itself (full-screen reading); other screens stay inside them.
        Box(Modifier.fillMaxSize().then(if (reading != null) Modifier else Modifier.safeDrawingPadding())) {
            val active = session
            val book = reading
            val statsId = bookStats
            if (readingStats && book == null) {
                com.hanreader.app.reader.StatsScreen(reader, onClose = { readingStats = false })
            } else if (statsId != null && book == null) {
                BookStatsScreen(reader, statsId, onClose = { bookStats = null })
            } else if (book != null) {
                ReaderScreen(reader, onClose = {
                    reading = null
                    reader.closeBook()
                })
            } else if (active != null) {
                BackHandler { session = null }
                ReviewSession(
                    mode = active,
                    cards = visibleCards,
                    examples = visibleExamples,
                    language = selectedLanguage,
                    speaker = speaker,
                    vm = vm,
                    settings = settings,
                    now = now,
                    onRated = { now = System.currentTimeMillis() },
                    onExit = {
                        session = null
                        now = System.currentTimeMillis()
                    }
                )
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopBar(
                            selected = selectedLanguage,
                            onSelect = vm::selectLanguage,
                            onSettings = { showSettings = true },
                            message = speaker.message
                        )
                    },
                    bottomBar = { BottomNav(tab) { tab = it } }
                ) { padding ->
                    when (tab) {
                        Tab.READ -> LibraryScreen(reader, padding, onOpen = { reading = it }, onStats = { bookStats = it }, onReadingStats = { readingStats = true })
                        Tab.TODAY -> TodayScreen(
                            cards = visibleCards,
                            settings = settings,
                            now = now,
                            padding = padding,
                            onStart = { session = it },
                            onShowHard = {
                                wordFilter = WordFilter.HARD
                                tab = Tab.WORDS
                            },
                            onImport = { tab = Tab.IMPORT }
                        )
                        Tab.WORDS -> DictionaryScreen(
                            cards = visibleCards,
                            examples = visibleExamples,
                            language = selectedLanguage,
                            speaker = speaker,
                            vm = vm,
                            padding = padding,
                            filter = wordFilter,
                            onFilter = { wordFilter = it },
                            onImport = { tab = Tab.IMPORT }
                        )
                        Tab.IMPORT -> ImportScreen(selectedLanguage, vm, padding)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    selected: String,
    onSelect: (String) -> Unit,
    onSettings: () -> Unit,
    message: String
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTitle()
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings) {
                Icon(
                    LexiconIcons.Settings,
                    contentDescription = "Настройки",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (message.isNotEmpty()) {
            Text(
                message,
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AppTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("汉读", style = MaterialTheme.typography.titleLarge, fontFamily = WordFont, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text("HanReader", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@Suppress("unused")
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = languages.firstOrNull { it.tag == selected } ?: languages.first()
    Box {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                Modifier.height(44.dp).padding(start = 9.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(26.dp).background(MaterialTheme.colorScheme.inverseSurface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        languageBadge(current.tag),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = WordFont,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(current.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(4.dp))
                Icon(LexiconIcons.ChevronDown, contentDescription = "Сменить язык", modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            languages.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = {
                        open = false
                        onSelect(option.tag)
                    },
                    leadingIcon = {
                        Text(
                            languageBadge(option.tag),
                            fontFamily = WordFont,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(24.dp)
                        )
                    },
                    trailingIcon = {
                        if (option.tag == selected) {
                            Icon(LexiconIcons.Check, contentDescription = "Выбран", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
            Tab.entries.forEach { option ->
                NavigationBarItem(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    icon = { Icon(option.icon(), contentDescription = null) },
                    label = { Text(option.title) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun LearningSettingsDialog(
    settings: LearningSettings,
    vm: LexiconViewModel,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Настройки · " + (languages.firstOrNull { it.tag == settings.language }?.title ?: ""),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Overline("Новые слова в день")
                Text(
                    "Общий лимит на узнавание и письмо. Старые повторения не ограничены.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 20).forEach { n ->
                        FilterChip(
                            selected = settings.dailyNewWords == n,
                            onClick = { vm.setDailyLimit(n) },
                            label = { Text("$n") }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 50).forEach { n ->
                        FilterChip(
                            selected = settings.dailyNewWords == n,
                            onClick = { vm.setDailyLimit(n) },
                            label = { Text("$n") }
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Overline("Автоматическая озвучка")
                Text(
                    "Слово или пример звучат при появлении карточки. В режиме письма ответ озвучивается после проверки.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                AutoSpeechMode.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            vm.setAutoSpeech(option)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.autoSpeech == option,
                            onClick = { vm.setAutoSpeech(option) }
                        )
                        Text(option.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Скорость · " + String.format(
                        java.util.Locale.ROOT, "%.1f×", settings.speechRate
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = settings.speechRate,
                    onValueChange = vm::setSpeechRate,
                    valueRange = 0.7f..1.3f,
                    steps = 5
                )
                Text(
                    "Все параметры сохраняются для каждого языка отдельно.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                BackupSection(vm)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CrashReportsSection()
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Готово") }
        }
    )
}
