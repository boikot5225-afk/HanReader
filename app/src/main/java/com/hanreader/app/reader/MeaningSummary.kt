package com.hanreader.app.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One visible meaning; the dictionary list is explicitly distinct from a contextual choice. */
@Composable
internal fun MeaningSummary(primary: String, senses: List<String>, contextual: Boolean, own: MeaningScope?, russian: Boolean) {
    var expanded by remember(primary, senses, own) { mutableStateOf(false) }
    val alternatives = senses.distinct().filter { it != primary }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(when {
            own != null -> "Моё значение · ${own.about}"
            !russian -> "Русского значения нет"
            contextual -> "По контексту"
            senses.distinct().size > 1 -> "Несколько значений · уточни по предложению"
            else -> "Словарное значение"
        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(primary, style = MaterialTheme.typography.titleLarge)
        if (alternatives.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "Скрыть другие значения" else "Другие значения (${alternatives.size})")
            }
            if (expanded) alternatives.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
