package com.hanreader.app.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.hanreader.app.LexiconDatabase
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

enum class FamiliarityLevel(val label: String) {
    NEW("Мало встреч"),
    REPEATED("Уже встречалось"),
    FAMILIAR("Встречалось без подсказки"),
    LOOKED_UP("Часто смотришь перевод")
}

data class WordFamiliarity(val lemma: String, val encounters: Long, val lookups: Long, val unassisted: Long, val unassistedDays: Int) {
    val level: FamiliarityLevel get() = when {
        lookups >= 2 && lookups >= encounters - encounters / 2 -> FamiliarityLevel.LOOKED_UP
        unassisted >= 3 && unassistedDays >= 2 -> FamiliarityLevel.FAMILIAR
        encounters >= 3 -> FamiliarityLevel.REPEATED
        else -> FamiliarityLevel.NEW
    }
}

@Composable
internal fun rememberWordFamiliarity(
    enabled: Boolean,
    chapter: ChapterContent?,
    engine: LanguageModule?,
    visible: List<WikiVisibleSpan>,
    unknownWords: Set<String>
): Map<String, WordFamiliarity> {
    if (!enabled || chapter == null || engine == null) return emptyMap()
    val context = LocalContext.current
    return key(chapter, engine) {
        val result by produceState(emptyMap<String, WordFamiliarity>(), visible, unknownWords) {
            if (visible.isEmpty() || unknownWords.isEmpty()) {
                value = emptyMap()
                return@produceState
            }
            delay(150)
            val visibleLemmas = withContext(Dispatchers.Default) {
                val words = LinkedHashMap<String, String>()
                for (span in visible) {
                    val block = chapter.blocks.getOrNull(span.block) as? ReaderBlock.Text ?: continue
                    val first = block.tokens.binarySearchBy(span.start) { it.start }.let { if (it < 0) -it - 1 else it }
                    for (index in first until block.tokens.size) {
                        val token = block.tokens[index]
                        if (token.start >= span.end) break
                        if (token.kind == TokenKind.HAN && token.text in unknownWords) {
                            words.getOrPut(token.text) { engine.lemma(token.text) }
                        }
                    }
                }
                words
            }
            if (visibleLemmas.isEmpty()) {
                value = emptyMap()
                return@produceState
            }
            val dao = LexiconDatabase.get(context).wordHistoryDao()
            val batches = visibleLemmas.values.distinct().chunked(400).map { dao.familiarity(engine.info.tag, it) }
            combine(batches) { rows ->
                val byLemma = rows.flatMap { it }.associateBy { it.lemma }
                visibleLemmas.mapNotNull { (word, lemma) -> byLemma[lemma]?.let { word to it } }.toMap()
            }.distinctUntilChanged().catch { emit(emptyMap()) }.collect { value = it }
        }
        result
    }
}
