package com.hanreader.app.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hanreader.app.WordFont
import com.hanreader.app.zh.TokenKind

/**
 * A word or phrase of the chapter worth a second look at its end: [key] is what «Знаю» and the
 * cards are about (the word itself; a chengyu or proverb is a «phrase»), [shown]
 * how it first stood in the text, [block]/[token] where.
 */
data class SummaryItem(
    val key: String,
    val shown: String,
    val phrase: Boolean,
    /** Times it stood in the chapter. */
    val count: Int,
    /** It stood more often than hints are drawn (Appearance.GLOSS_REPEATS): read without a hint at the end. */
    val faded: Boolean,
    /** The reader tapped it in this chapter. */
    val tapped: Boolean,
    val block: Int,
    val token: Int
)

/**
 * The end-of-chapter review. The reader mostly reads the hints over the words and seldom taps them,
 * so the words come from the hints: the words of the chapter that had one (not known yet), most
 * frequent first, those whose hint faded (seen several times, last without help) and those tapped
 * ahead; the phrases that had one are listed apart. Known words, names and words already in the
 * cards are left out.
 */
object ChapterSummary {
    const val WORDS = 12
    const val PHRASES = 4

    /** The key of a word on the page: the word itself. */
    @Suppress("UNUSED_PARAMETER")
    fun keyOf(t: RToken, engine: ZhEngine): String? = t.text

    fun items(
        chapter: ChapterContent,
        engine: ZhEngine,
        /** The hints of this chapter (null: hints are off, then every word not known counts). */
        glossFor: Map<String, String>?,
        repeats: Int,
        isKnown: (word: String, key: String) -> Boolean,
        skip: (word: String, key: String) -> Boolean,
        tapped: Set<String>
    ): List<SummaryItem> {
        class Acc(val key: String, val shown: String, val phrase: Boolean, val block: Int, val token: Int) {
            var count = 0
            var hinted = 0
        }
        val acc = LinkedHashMap<String, Acc>()
        chapter.blocks.forEachIndexed { bi, b ->
            val tokens = (b as? ReaderBlock.Text)?.tokens ?: return@forEachIndexed
            if (b.heading) return@forEachIndexed
            for ((ti, t) in tokens.withIndex()) {
                if (t.kind != TokenKind.HAN) continue
                val key = keyOf(t, engine) ?: continue
                // Set expressions (chengyu, proverbs) are listed apart from words.
                val a = acc.getOrPut(key) {
                    Acc(key, b.text.substring(t.start, t.end), idiomKind(engine.lookup(key)?.gloss) != null, bi, ti)
                }
                a.count++
                if (glossFor == null || glossOf(glossFor, t, bi) != null) a.hinted++
            }
        }
        val candidates = acc.values.filter { a ->
            val word = (chapter.blocks[a.block] as ReaderBlock.Text).tokens[a.token].text
            (a.hinted > 0 || a.key in tapped) && !isKnown(word, a.key) && !skip(word, a.key)
        }
        fun item(a: Acc) = SummaryItem(a.key, a.shown, a.phrase, a.count, a.count > repeats, a.key in tapped, a.block, a.token)
        // Tapped first, then those whose hint faded, then by how often they stood in the chapter.
        fun score(a: Acc) = (if (a.key in tapped) 1000 else 0) + (if (a.count > repeats) 100 else 0) + a.count
        val phrases = candidates.filter { it.phrase }.sortedByDescending(::score).take(PHRASES).map(::item)
        val words = candidates.filter { !it.phrase }.sortedByDescending(::score).take(WORDS + PHRASES - phrases.size).map(::item)
        return words + phrases
    }
}

/** «Итог главы: 12 слов и 3 выражения →» at the end of a chapter. */
@Composable
internal fun ChapterSummaryPlaque(items: List<SummaryItem>, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    if (items.isEmpty()) return
    val words = items.count { !it.phrase }
    val phrases = items.size - words
    val what = listOfNotNull(
        if (words > 0) "$words ${plural(words, "слово", "слова", "слов")}" else null,
        if (phrases > 0) "$phrases ${plural(phrases, "выражение", "выражения", "выражений")}" else null
    ).joinToString(" и ")
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 4.dp) {
        TextButton(onClick = onOpen) { Text("Итог главы: $what →", color = MaterialTheme.colorScheme.inverseOnSurface) }
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val d = n % 10
    val dd = n % 100
    return when {
        d == 1 && dd != 11 -> one
        d in 2..4 && dd !in 12..14 -> few
        else -> many
    }
}

/**
 * The review: each word first without its translation (remember it, then look), then the meaning
 * here and the sentence of the chapter, and «Знаю» / «Не знаю» / «В карточки». «Знаю» takes the hint
 * off the word in the whole book.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterSummarySheet(
    chapter: ChapterContent,
    items: List<SummaryItem>,
    engine: ZhEngine,
    /** Share of the chapter's words known now. */
    percent: Int?,
    onKnown: (String, Boolean) -> Unit,
    onSave: (SummaryItem, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val startPercent = remember { percent }
    val decided = remember { mutableStateMapOf<String, String>() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Итог главы", style = MaterialTheme.typography.titleLarge)
                Text(chapter.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (percent != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Знакомо $percent% слов главы" + if (startPercent != null && startPercent != percent) " (было $startPercent%)" else "",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "Сначала вспомни значение, потом открой. «Знаю» убирает подсказку над словом во всей книге.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(items, key = { it.key }) { item ->
                SummaryRow(chapter, item, engine, decided[item.key],
                    onKnown = { known -> onKnown(item.key, known); decided[item.key] = if (known) "Знаю" else "Не знаю" },
                    onSave = { translation, sentence -> onSave(item, translation, sentence); decided[item.key] = "В карточках" })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun SummaryRow(
    chapter: ChapterContent,
    item: SummaryItem,
    engine: ZhEngine,
    decision: String?,
    onKnown: (Boolean) -> Unit,
    onSave: (String, String) -> Unit
) {
    var open by remember(item.key) { mutableStateOf(false) }
    val block = chapter.blocks[item.block] as ReaderBlock.Text
    val t = block.tokens[item.token]
    val translation = remember(item.key) {
        (engine.cardTranslation(t.text, t.pinyin.takeIf { it.isNotEmpty() })
            ?: engine.glossShort(t.text, t.pinyin.takeIf { it.isNotEmpty() })).orEmpty()
    }
    val sentence = remember(item.key) { ChapterBuilder.sentence(block.text, t.start, t.start + item.shown.length) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.key, style = MaterialTheme.typography.titleMedium, fontFamily = WordFont, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            val note = listOfNotNull(
                "${item.count}×",
                if (item.faded) "без подсказки" else null,
                if (item.tapped) "смотрел" else null
            ).joinToString(" · ")
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!open) {
            TextButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Показать значение") }
        } else {
            if (translation.isNotEmpty()) Text(translation, style = MaterialTheme.typography.bodyLarge)
            Text(sentence, style = MaterialTheme.typography.bodyMedium, fontFamily = WordFont, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (decision != null) {
            Text("✓ $decision", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onKnown(true) }) { Text("Знаю") }
                OutlinedButton(onClick = { onKnown(false) }) { Text("Не знаю") }
                TextButton(onClick = { onSave(translation, sentence) }) { Text("В карточки") }
            }
            Spacer(Modifier.width(0.dp))
        }
    }
}
