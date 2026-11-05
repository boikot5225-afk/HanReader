package com.hanreader.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class WordFilter(val title: String) {
    ALL("Все"),
    NEW("Новые"),
    HARD("Трудные"),
    LEARNED("Выучены");

    fun accepts(card: WordCard): Boolean = when (this) {
        ALL -> true
        NEW -> card.introducedAt == 0L
        HARD -> card.totalLapses() > 0
        LEARNED -> card.isLearned()
    }
}

private fun rowShape(index: Int, count: Int): Shape {
    val r = 20.dp
    return when {
        count == 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        index == count - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RectangleShape
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(
    cards: List<WordCard>,
    examples: List<CardExample>,
    language: String,
    speaker: Speaker,
    vm: LexiconViewModel,
    padding: PaddingValues,
    filter: WordFilter,
    onFilter: (WordFilter) -> Unit,
    onImport: () -> Unit
) {
    var openId by rememberSaveable(language) { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<WordCard?>(null) }
    var exampleTarget by remember { mutableStateOf<ExampleTarget?>(null) }
    var editingCard by remember { mutableStateOf<WordCard?>(null) }
    var contextText by remember { mutableStateOf("") }
    var contextTranslation by remember { mutableStateOf("") }
    var contextError by remember { mutableStateOf("") }
    var contextSaving by remember { mutableStateOf(false) }
    var search by rememberSaveable(language) { mutableStateOf("") }
    val counts = remember(cards) { WordFilter.entries.associateWith { f -> cards.count { f.accepts(it) } } }
    val filtered = remember(cards, search, filter) {
        val query = search.trim()
        cards.filter { card ->
            filter.accepts(card) && (
                query.isEmpty() ||
                    card.word.contains(query, ignoreCase = true) ||
                    card.reading.contains(query, ignoreCase = true) ||
                    card.translation.contains(query, ignoreCase = true)
                )
        }
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text("Словарь", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            Text(
                "${cards.size} " + RussianText.plural(cards.size, "слово", "слова", "слов"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        if (cards.isEmpty()) {
            Panel(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Словарь пуст", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Скопируй карточки из ИИ и вставь их во вкладке «Импорт».",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BigButton("Перейти к импорту", onClick = onImport)
                }
            }
        } else {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Слово, транскрипция или перевод") },
                leadingIcon = { Icon(LexiconIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }) {
                            Icon(LexiconIcons.Close, contentDescription = "Очистить поиск")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WordFilter.entries.forEach { option ->
                    val selected = option == filter
                    val count = counts[option] ?: 0
                    FilterChip(
                        selected = selected,
                        onClick = { onFilter(option) },
                        label = { Text(if (option == WordFilter.ALL) option.title else option.title + " · " + count) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                            selectedLabelColor = MaterialTheme.colorScheme.inverseOnSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = Color.Transparent
                        )
                    )
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "Совпадений не найдено",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                itemsIndexed(filtered, key = { _, card -> card.id }) { index, card ->
                    WordRow(card, rowShape(index, filtered.size), showDivider = index < filtered.lastIndex) {
                        openId = card.id
                    }
                }
            }
        }
    }

    val sheetCard = cards.firstOrNull { it.id == openId }
    if (sheetCard != null) {
        ModalBottomSheet(
            onDismissRequest = { openId = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            val contexts = examples.filter { it.cardId == sheetCard.id }.ifEmpty {
                listOf(
                    CardExample(
                        cardId = sheetCard.id,
                        example = sheetCard.example,
                        exampleTranslation = sheetCard.exampleTranslation
                    )
                )
            }
            WordDetails(
                card = sheetCard,
                contexts = contexts,
                onSpeak = { speaker.speak(it, language) },
                onEdit = { editingCard = sheetCard },
                onAddExample = {
                    exampleTarget = ExampleTarget(sheetCard, null, canDelete = false)
                    contextText = ""
                    contextTranslation = ""
                    contextError = ""
                },
                onEditExample = { ex ->
                    exampleTarget = ExampleTarget(sheetCard, ex, canDelete = contexts.size > 1 && ex.id != 0L)
                    contextText = ex.example
                    contextTranslation = ex.exampleTranslation
                    contextError = ""
                },
                onDelete = { deleting = sheetCard }
            )
        }
    }

    editingCard?.let { card ->
        EditCardDialog(card = card, vm = vm, onClose = { editingCard = null })
    }

    exampleTarget?.let { target ->
        val card = target.card
        val editing = target.example
        AlertDialog(
            onDismissRequest = { if (!contextSaving) exampleTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    (if (editing == null) "Новый пример · " else "Пример · ") + card.word,
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = contextText,
                        onValueChange = { contextText = it; contextError = "" },
                        label = { Text("Предложение на изучаемом языке") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = contextTranslation,
                        onValueChange = { contextTranslation = it; contextError = "" },
                        label = { Text("Перевод предложения") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (contextError.isNotEmpty()) {
                        Text(contextError, color = MaterialTheme.colorScheme.error)
                    }
                    if (editing != null && target.canDelete) {
                        TextButton(
                            enabled = !contextSaving,
                            onClick = {
                                contextSaving = true
                                vm.deleteExample(card.id, editing.id) { result ->
                                    contextSaving = false
                                    result.fold(
                                        onSuccess = { removed ->
                                            if (removed) exampleTarget = null
                                            else contextError = "У слова должен остаться хотя бы один пример."
                                        },
                                        onFailure = { error ->
                                            contextError = error.message ?: "Не удалось удалить пример."
                                        }
                                    )
                                }
                            }
                        ) {
                            Icon(
                                LexiconIcons.Trash,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Удалить этот пример", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !contextSaving &&
                        contextText.isNotBlank() && contextTranslation.isNotBlank(),
                    onClick = {
                        contextSaving = true
                        val onResult: (Result<Boolean>) -> Unit = { result ->
                            contextSaving = false
                            result.fold(
                                onSuccess = { saved ->
                                    if (saved) exampleTarget = null
                                    else contextError = "Такой пример уже есть у этого слова."
                                },
                                onFailure = { error ->
                                    contextError = error.message ?: "Не удалось сохранить пример."
                                }
                            )
                        }
                        if (editing == null) {
                            vm.addContext(card.id, contextText, contextTranslation, onResult)
                        } else {
                            vm.editExample(card.id, editing.id, contextText, contextTranslation, onResult)
                        }
                    }
                ) { Text(if (contextSaving) "Сохранение" else "Сохранить") }
            },
            dismissButton = {
                TextButton(enabled = !contextSaving, onClick = { exampleTarget = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Удалить слово?", style = MaterialTheme.typography.headlineSmall) },
            text = { Text(card.word + " — " + card.translation + ". Прогресс и примеры будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(card.id)
                    deleting = null
                    openId = null
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun WordRow(card: WordCard, shape: Shape, showDivider: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    card.word,
                    fontFamily = WordFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 56.dp, max = 140.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(card.translation, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (card.reading.isNotEmpty()) {
                        Text(
                            card.reading,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                ProgressDots(card.successfulReviews, hard = card.totalLapses() > 0)
            }
            if (showDivider) {
                HorizontalDivider(
                    Modifier.padding(start = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun WordDetails(
    card: WordCard,
    contexts: List<CardExample>,
    onSpeak: (String) -> Unit,
    onEdit: () -> Unit,
    onAddExample: () -> Unit,
    onEditExample: (CardExample) -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(card.word, style = MaterialTheme.typography.displaySmall)
        if (card.reading.isNotEmpty()) {
            Text(card.reading, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(card.translation, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Normal)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeakButton("Слово", { onSpeak(card.word) })
            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier.heightIn(min = 44.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Icon(LexiconIcons.Pen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Изменить")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Overline("Примеры · " + contexts.size)
        contexts.forEachIndexed { index, ctx ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ctx.example, fontFamily = WordFont, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
                    Text(
                        ctx.exampleTranslation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onSpeak(ctx.example) }) {
                    Icon(LexiconIcons.Volume, contentDescription = "Произнести пример " + (index + 1))
                }
                IconButton(onClick = { onEditExample(ctx) }) {
                    Icon(LexiconIcons.Pen, contentDescription = "Изменить пример " + (index + 1))
                }
            }
        }
        TextButton(onClick = onAddExample) {
            Icon(LexiconIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Добавить пример")
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Overline("Прогресс")
        ProgressLine("Узнавание", card.successfulReviews, card.intervalDays, card.recognitionLapses)
        ProgressLine("Письмо", card.writingSuccessfulReviews, card.writingIntervalDays, card.writingLapses)
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onDelete) {
            Icon(
                LexiconIcons.Trash,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("Удалить слово", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ProgressLine(title: String, successes: Int, intervalDays: Int, lapses: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                (if (intervalDays > 0) "интервал " + RussianText.days(intervalDays) else "ещё не повторялось") +
                    " · ошибок: " + lapses,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ProgressDots(successes, hard = lapses > 0)
    }
}

/** Which example the example dialog edits; example == null adds a new one. */
private data class ExampleTarget(val card: WordCard, val example: CardExample?, val canDelete: Boolean)

/** Edit word, transcription and translation; progress and examples stay. */
@Composable
private fun EditCardDialog(card: WordCard, vm: LexiconViewModel, onClose: () -> Unit) {
    var word by rememberSaveable(card.id) { mutableStateOf(card.word) }
    var reading by rememberSaveable(card.id) { mutableStateOf(card.reading) }
    var translation by rememberSaveable(card.id) { mutableStateOf(card.translation) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Изменить слово", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it; error = "" },
                    label = { Text("Слово") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = WordFont),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = reading,
                    onValueChange = { reading = it; error = "" },
                    label = { Text("Транскрипция (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it; error = "" },
                    label = { Text("Перевод") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Прогресс повторений и примеры сохранятся.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && word.isNotBlank() && translation.isNotBlank(),
                onClick = {
                    saving = true
                    vm.editCard(card, word, reading, translation) { result ->
                        saving = false
                        result.fold(
                            onSuccess = { saved ->
                                if (saved) onClose()
                                else error = "Слово с таким переводом уже есть в словаре."
                            },
                            onFailure = { error = it.message ?: "Не удалось сохранить." }
                        )
                    }
                }
            ) { Text(if (saving) "Сохранение" else "Сохранить") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onClose) { Text("Отмена") }
        }
    )
}
