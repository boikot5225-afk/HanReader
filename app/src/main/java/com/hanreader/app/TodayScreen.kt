package com.hanreader.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Russian: Locale = Locale.forLanguageTag("ru")

@Composable
fun TodayScreen(
    cards: List<WordCard>,
    settings: LearningSettings,
    now: Long,
    padding: PaddingValues,
    onStart: (ReviewMode) -> Unit,
    onShowHard: () -> Unit,
    onImport: () -> Unit
) {
    val recognition = remember(cards, now, settings.dailyNewWords) {
        ReviewQueue.build(cards, ReviewMode.RECOGNITION, now, dailyLimit = settings.dailyNewWords)
    }
    val writing = remember(cards, now, settings.dailyNewWords) {
        ReviewQueue.build(cards, ReviewMode.WRITING, now, dailyLimit = settings.dailyNewWords)
    }
    val newCount = recognition.due.count { it.introducedAt == 0L }
    val repeatCount = recognition.due.size - newCount
    val learned = cards.count { it.isLearned() }
    val hard = remember(cards) {
        cards.filter { it.totalLapses() > 0 }
            .sortedByDescending { it.totalLapses() }
            .take(3)
    }
    val nextDue = cards.asSequence()
        .filter { it.introducedAt != 0L }
        .map { it.nextReviewAt }
        .filter { it > now }
        .minOrNull()
    val date = remember(now / 3_600_000L) {
        SimpleDateFormat("EEEE, d MMMM", Russian).format(Date(now))
            .replaceFirstChar { it.titlecase(Russian) }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Overline(date)
            Text("Сегодня", style = MaterialTheme.typography.headlineLarge)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface
        ) {
            val muted = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f)
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (cards.isEmpty()) {
                    Text("Словарь пока пуст", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Попроси ИИ создать карточки и вставь ответ во вкладке «Импорт».",
                        color = muted
                    )
                    HeroPrimaryButton("Перейти к импорту", enabled = true) { onImport() }
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                recognition.due.size.toString(),
                                fontFamily = WordFont,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 56.sp,
                                lineHeight = 60.sp
                            )
                            Text(
                                if (recognition.due.isEmpty()) "слов к повторению"
                                else RussianText.plural(
                                    recognition.due.size,
                                    "слово ждёт повторения",
                                    "слова ждут повторения",
                                    "слов ждут повторения"
                                ),
                                color = muted
                            )
                        }
                        if (recognition.due.isNotEmpty()) {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Legend(HeroReviewColor, "$repeatCount " + RussianText.plural(repeatCount, "повторение", "повторения", "повторений"), muted)
                                Legend(HeroNewColor, "$newCount " + RussianText.plural(newCount, "новое", "новых", "новых"), muted)
                            }
                        }
                    }
                    if (recognition.due.isEmpty()) {
                        Text(
                            when {
                                recognition.unseenTotal > 0 && recognition.remainingNew == 0 ->
                                    "Лимит новых слов на сегодня исчерпан."
                                nextDue != null ->
                                    "Всё повторено. Следующее слово — через " + RussianText.duration(nextDue - now) + "."
                                else -> "Всё повторено."
                            },
                            color = muted
                        )
                    }
                    HeroPrimaryButton("Начать: карточки", enabled = recognition.due.isNotEmpty()) {
                        onStart(ReviewMode.RECOGNITION)
                    }
                    OutlinedButton(
                        onClick = { onStart(ReviewMode.WRITING) },
                        enabled = writing.due.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            disabledContentColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.45f)
                        )
                    ) {
                        Icon(LexiconIcons.Pen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Письмо · " + writing.due.size)
                    }
                }
            }
        }

        if (cards.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    title = "Новые сегодня",
                    value = recognition.introducedToday.toString(),
                    total = " / " + recognition.dailyLimit,
                    fraction = recognition.introducedToday.toFloat() / recognition.dailyLimit.coerceAtLeast(1),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    title = "Выучено",
                    value = learned.toString(),
                    total = " / " + cards.size,
                    fraction = learned.toFloat() / cards.size.coerceAtLeast(1),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (hard.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Трудные слова", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onShowHard) { Text("Все") }
            }
            Panel(Modifier.fillMaxWidth()) {
                hard.forEachIndexed { index, card ->
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
                            modifier = Modifier.widthIn(min = 56.dp, max = 130.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(card.translation, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (card.reading.isNotEmpty()) {
                                Text(
                                    card.reading,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        val lapses = card.totalLapses()
                        Pill(
                            "$lapses " + RussianText.plural(lapses, "ошибка", "ошибки", "ошибок"),
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    if (index < hard.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.inverseOnSurface,
            contentColor = MaterialTheme.colorScheme.inverseSurface,
            disabledContainerColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.18f),
            disabledContentColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.5f)
        )
    ) {
        Icon(LexiconIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Legend(dot: Color, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun StatTile(
    title: String,
    value: String,
    total: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Panel(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineSmall)
                Text(
                    total,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            ProgressBar(fraction, Modifier.fillMaxWidth(), color = color)
        }
    }
}
