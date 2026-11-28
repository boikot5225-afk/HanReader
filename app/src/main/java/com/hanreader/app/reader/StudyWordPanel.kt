package com.hanreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hanreader.app.Panel
import com.hanreader.app.WordCard

/** A word in the cards, tapped in the text: where it stands, a quick review and its picture. */
@Composable
internal fun StudyWordPanel(
    cards: List<WordCard>,
    meaningHere: String,
    sentence: String,
    onGrade: (WordCard, Boolean) -> Unit,
    onPeek: (WordCard) -> Unit
) {
    val card = StudyStages.cardFor(cards, meaningHere) ?: return
    val now = remember(card.id) { System.currentTimeMillis() }
    val stage = StudyStages.of(card)
    // Opening the card of a word without its hint is a lookup: the review comes sooner.
    var peeked by remember(card.id) { mutableStateOf(false) }
    LaunchedEffect(card.id) { if (StudyStages.peeked(card, now) != null) { peeked = true; onPeek(card) } }
    var answer by remember(card.id) { mutableStateOf<Boolean?>(null) }
    Panel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("В карточках · ${stage.label}", style = MaterialTheme.typography.titleSmall)
            if (stage != StudyStage.LEARNED) Text(
                (if (peeked) "Подсмотрел — повтор в течение суток" else StudyStages.nextReview(card, now)) +
                    " · успешных повторов: ${card.successfulReviews}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (StudyStages.otherMeaning(card, meaningHere)) Text(
                "В карточке: «${card.translation}». Здесь — другое значение: «$meaningHere».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (stage != StudyStage.LEARNED) when (answer) {
                null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (StudyStages.canConfirm(card, now)) OutlinedButton(onClick = { answer = true; onGrade(card, true) }) { Text("Помню") }
                    OutlinedButton(onClick = { answer = false; onGrade(card, false) }) { Text("Забыл") }
                }
                true -> Text("Засчитано как повтор.", style = MaterialTheme.typography.bodySmall)
                false -> Text(if (StudyStages.of(com.hanreader.app.ReviewSchedule.rate(card, false, now, com.hanreader.app.ReviewMode.RECOGNITION)).hint)
                    "Повтор через 10 минут; подсказка вернётся над словом." else "Повтор через 10 минут.", style = MaterialTheme.typography.bodySmall)
            }
            com.hanreader.app.images.CardPictureEditor(card, sentence = card.example.ifBlank { sentence })
        }
    }
}
