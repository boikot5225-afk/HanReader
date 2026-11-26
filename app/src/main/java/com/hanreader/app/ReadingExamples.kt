package com.hanreader.app

import androidx.room.Embedded
import androidx.room.withTransaction
import com.hanreader.app.reader.ChapterBuilder
import com.hanreader.app.reader.WordEncounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class StudyEncounter(@Embedded val encounter: WordEncounter, val title: String,
    val chapterTitle: String, val sourceKey: String, val textStart: Int, val text: String)

data class ReadingExample(val encounterId: String, val sentence: String, val source: String)

internal object ReadingExamples {
    private val spaces = Regex("[\\s\\p{Z}]+")
    fun textKey(text: String) = text.trim().replace(spaces, " ")
    fun keys(word: String): List<String> = listOf(textKey(word), textKey(word).lowercase(Locale.ROOT)).distinct()
    fun sentence(row: StudyEncounter): String? {
        val start = row.encounter.start.toLong() - row.textStart
        val end = row.encounter.end.toLong() - row.textStart
        if (start < 0 || end <= start || end > row.text.length) return null
        return ChapterBuilder.sentence(row.text, start.toInt(), end.toInt()).takeIf { it.isNotBlank() }
    }
    fun suggestions(card: WordCard, rows: List<StudyEncounter>, saved: List<CardExample>): List<ReadingExample> {
        val used = (saved.filter { it.cardId == card.id }.map { textKey(it.example) } + textKey(card.example)).toHashSet()
        return rows.mapNotNull { row ->
            if (row.encounter.language != card.language || row.encounter.lemma !in keys(card.word)) return@mapNotNull null
            val sentence = sentence(row) ?: return@mapNotNull null
            val key = textKey(sentence)
            if (key == textKey(row.encounter.original) || !used.add(key)) return@mapNotNull null
            val source = listOf(row.title, row.chapterTitle).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            ReadingExample(row.encounter.id, sentence,
                if (row.sourceKey.startsWith("wiki:")) "Википедия · $source" else source)
        }.take(5)
    }
}

internal class ReadingExamplesRepository(private val db: LexiconDatabase) {
    fun observe(card: WordCard) = db.wordHistoryDao().studyContexts(card.language, ReadingExamples.keys(card.word))

    suspend fun add(card: WordCard, encounterId: String): Boolean = withContext(Dispatchers.Default) {
        db.withTransaction {
            val cards = db.cardDao()
            val current = cards.getById(card.id) ?: error("Карточка уже удалена")
            require(current.language == card.language && current.word == card.word && current.translation == card.translation) {
                "Карточка изменилась. Открой её заново."
            }
            val row = db.wordHistoryDao().studyContext(encounterId) ?: error("Фрагмент уже удалён из истории")
            require(row.encounter.language == current.language && row.encounter.lemma in ReadingExamples.keys(current.word)) {
                "Этот фрагмент относится к другому слову"
            }
            val sentence = ReadingExamples.sentence(row) ?: error("Не удалось прочитать контекст")
            val examples = cards.examplesFor(current.id)
            val key = ReadingExamples.textKey(sentence)
            if (ReadingExamples.textKey(current.example) == key || examples.any { ReadingExamples.textKey(it.example) == key }) return@withTransaction false
            // Legacy cards may have no card_examples rows yet. Keep their original example in rotation.
            if (current.example.isNotBlank() && examples.none { ReadingExamples.textKey(it.example) == ReadingExamples.textKey(current.example) }) {
                cards.insertExample(CardExample(cardId = current.id, example = current.example, exampleTranslation = current.exampleTranslation))
            }
            cards.insertExample(CardExample(cardId = current.id, example = sentence, exampleTranslation = "")) > 0
        }
    }
}
