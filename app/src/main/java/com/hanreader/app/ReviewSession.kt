package com.hanreader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen review session in one mode. Recognition and writing keep
 * independent schedules and share the daily quota for new words.
 */
@Composable
fun ReviewSession(
    mode: ReviewMode,
    cards: List<WordCard>,
    examples: List<CardExample>,
    language: String,
    speaker: Speaker,
    vm: LexiconViewModel,
    settings: LearningSettings,
    now: Long,
    onRated: () -> Unit,
    onExit: () -> Unit
) {
    val writingMode = mode == ReviewMode.WRITING
    // Stop speech when leaving the session as well as when changing cards.
    DisposableEffect(speaker) {
        onDispose { speaker.stop() }
    }
    val plan = ReviewQueue.build(cards, mode, now, dailyLimit = settings.dailyNewWords)
    val due = plan.due
    val current = due.firstOrNull()
    val activeExample = current?.let { ExampleSelection.choose(it, mode, examples) }
    var done by rememberSaveable(mode, language) { mutableIntStateOf(0) }

    // Answer state resets for each next card.
    var revealed by remember(current?.id) { mutableStateOf(false) }
    var typed by remember(current?.id) { mutableStateOf("") }
    var checked by remember(current?.id) { mutableStateOf(false) }
    var submitted by remember(language, current?.id) { mutableStateOf(false) }

    // Never give away a written answer until the user has pressed Check.
    LaunchedEffect(
        current?.id, activeExample?.id, language, checked,
        settings.autoSpeech, settings.speechRate, speaker.ready
    ) {
        speaker.stop()
        val card = current
        if (card != null && speaker.ready && (!writingMode || checked)) {
            speaker.rate = settings.speechRate
            speaker.speakAutomatically(
                card.word, activeExample?.example ?: card.example,
                language, settings.autoSpeech
            )
        }
    }

    fun grade(card: WordCard, remembered: Boolean) {
        submitted = true
        vm.rate(card, remembered, mode)
        done += 1
        onRated()
    }

    fun alreadyKnown(card: WordCard) {
        submitted = true
        vm.markAlreadyKnown(card, mode)
        done += 1
        onRated()
    }

    val total = done + due.size
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit) {
                Icon(LexiconIcons.Close, contentDescription = "Завершить повторение")
            }
            ProgressBar(
                fraction = if (total == 0) 1f else done.toFloat() / total,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (current != null) "${done + 1} / $total" else "$done / $done",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (speaker.message.isNotEmpty()) {
            Text(
                speaker.message,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (current == null) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        LexiconIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    if (done > 0) "Готово!" else "Пока нечего повторять",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        done > 0 -> "Повторено: $done " + RussianText.plural(done, "слово", "слова", "слов") + "."
                        plan.unseenTotal > 0 && plan.remainingNew == 0 ->
                            "Лимит новых слов на сегодня исчерпан. Плановые повторения остаются доступны."
                        else -> "В этом режиме сейчас нет карточек."
                    },
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(28.dp))
                BigButton("На главную", onClick = onExit)
            }
        } else {
            val exampleText = activeExample?.example ?: current.example
            val exampleTranslation = activeExample?.exampleTranslation ?: current.exampleTranslation
            val isNew = current.introducedAt == 0L
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Overline(if (writingMode) "Письмо" else "Узнавание")
                    if (isNew) {
                        Pill(
                            "Новое слово",
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                if (writingMode) {
                    WritingCard(
                        card = current,
                        language = language,
                        exampleText = exampleText,
                        exampleTranslation = exampleTranslation
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        enabled = !checked && !submitted,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Твой ответ") },
                        isError = checked && !WritingExercise.matches(typed, current.word),
                        shape = RoundedCornerShape(18.dp),
                        textStyle = MaterialTheme.typography.headlineSmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            disabledContainerColor = MaterialTheme.colorScheme.surface,
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (typed.isNotBlank() && !checked && !submitted) checked = true
                            }
                        )
                    )
                    if (checked) {
                        WritingResult(
                            card = current,
                            typed = typed,
                            exampleText = exampleText,
                            onSpeakWord = { speaker.speak(current.word, language) },
                            onSpeakExample = { speaker.speak(exampleText, language) }
                        )
                    }
                } else {
                    RecognitionCard(
                        card = current,
                        language = language,
                        exampleText = exampleText,
                        exampleTranslation = exampleTranslation,
                        revealed = revealed,
                        onSpeakWord = { speaker.speak(current.word, language) },
                        onSpeakExample = { speaker.speak(exampleText, language) }
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (writingMode) {
                    if (!checked) {
                        BigButton(
                            "Проверить",
                            enabled = typed.isNotBlank() && !submitted,
                            onClick = { checked = true }
                        )
                    } else if (WritingExercise.matches(typed, current.word)) {
                        BigButton("Дальше", enabled = !submitted, onClick = { grade(current, true) })
                    } else {
                        BigButton(
                            "Дальше · повтор через 10 мин",
                            enabled = !submitted,
                            onClick = { grade(current, false) }
                        )
                        TextButton(
                            enabled = !submitted,
                            onClick = { grade(current, true) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Мой вариант тоже верный — засчитать") }
                    }
                } else if (!revealed) {
                    BigButton("Показать перевод", enabled = !submitted, onClick = { revealed = true })
                } else {
                    val rated = ReviewSchedule.rate(current, true, now, mode)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GradeButton(
                            title = "Не помню",
                            hint = "через 10 мин",
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                            enabled = !submitted,
                            modifier = Modifier.weight(1f)
                        ) { grade(current, false) }
                        GradeButton(
                            title = "Помню",
                            hint = "через " + RussianText.days(rated.intervalDays),
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            enabled = !submitted,
                            modifier = Modifier.weight(1f)
                        ) { grade(current, true) }
                    }
                }
                if (isNew && !checked) {
                    TextButton(
                        enabled = !submitted,
                        onClick = { alreadyKnown(current) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            "Уже знаю — проверить через 30 дней",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecognitionCard(
    card: WordCard,
    language: String,
    exampleText: String,
    exampleTranslation: String,
    revealed: Boolean,
    onSpeakWord: () -> Unit,
    onSpeakExample: () -> Unit
) {
    val size = wordFontSize(card.word)
    Panel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                card.word,
                fontFamily = WordFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = size,
                lineHeight = size * 1.2f,
                textAlign = TextAlign.Center
            )
            if (card.reading.isNotEmpty()) {
                Text(
                    card.reading,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SpeakButton("Слово", onSpeakWord)
                SpeakButton("Фраза", onSpeakExample)
            }
            HorizontalDivider(
                Modifier.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Text(
                highlighted(
                    exampleText,
                    WritingExercise.occurrences(exampleText, card.word, language),
                    SpanStyle(
                        background = MaterialTheme.colorScheme.secondaryContainer,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ),
                style = MaterialTheme.typography.titleLarge,
                fontFamily = WordFont,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center
            )
            if (revealed) {
                Spacer(Modifier.height(10.dp))
                Text(card.translation, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    exampleTranslation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    "Вспомни перевод, затем открой ответ",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WritingCard(
    card: WordCard,
    language: String,
    exampleText: String,
    exampleTranslation: String
) {
    // Do not show any unmasked target-language text before checking.
    val cloze = WritingExercise.cloze(exampleText, card.word, language)
    Panel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Напиши слово",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(card.translation, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                exampleTranslation,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (cloze != null) {
                Text(
                    cloze,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = WordFont,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                "Введи слово в той форме, в которой оно сохранено.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WritingResult(
    card: WordCard,
    typed: String,
    exampleText: String,
    onSpeakWord: () -> Unit,
    onSpeakExample: () -> Unit
) {
    val correct = WritingExercise.matches(typed, card.word)
    val wrong = if (correct) emptySet() else WritingExercise.mismatches(typed, card.word)
    val answer = card.word.trim()
    val container = if (correct) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (correct) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = content
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (correct) LexiconIcons.Check else LexiconIcons.Wrong,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        correct -> "Верно!"
                        wrong.size == 1 -> "Почти: ошибка в одном знаке"
                        else -> "Пока неверно"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(
                if (correct) AnnotatedString(answer) else highlighted(
                    answer,
                    wrong.map { it..it },
                    SpanStyle(color = MaterialTheme.colorScheme.error, textDecoration = TextDecoration.Underline)
                ),
                fontFamily = WordFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 34.sp,
                lineHeight = 42.sp
            )
            if (card.reading.isNotEmpty()) Text(card.reading, style = MaterialTheme.typography.bodyLarge)
            Text(exampleText, fontFamily = WordFont, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeakButton("Слово", onSpeakWord)
                SpeakButton("Фраза", onSpeakExample)
            }
        }
    }
}

@Composable
private fun GradeButton(
    title: String,
    hint: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.labelMedium)
        }
    }
}
