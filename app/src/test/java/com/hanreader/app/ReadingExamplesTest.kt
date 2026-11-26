package com.hanreader.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingExamplesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: LexiconDatabase
    private lateinit var repo: ReadingExamplesRepository
    private lateinit var card: WordCard
    private fun row(text: String, original: String = "perro", lemma: String = "perro", language: String = "es-ES", seed: Char = 'a'): Pair<ReadingFragment, WordEncounter> {
        val f = ReadingFragment(seed.toString().repeat(64), "wiki:${language.substringBefore('-')}:42", null, "Статья", "Раздел", 0, 0, 0, text)
        val start = text.indexOf(original)
        val w = WordEncounter(WordHistory.wordId(f.id, start, start + original.length, lemma), f.id, language, lemma, original, start, start + original.length, 100)
        return f to w
    }
    private suspend fun save(row: Pair<ReadingFragment, WordEncounter>) {
        db.wordHistoryDao().record(EncounterBatch(listOf(row.first), listOf(row.second)))
    }
    private fun study(row: Pair<ReadingFragment, WordEncounter>) = StudyEncounter(row.second, row.first.title,
        row.first.chapterTitle, row.first.sourceKey, row.first.textStart, row.first.text)
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(app, LexiconDatabase::class.java).build()
        repo = ReadingExamplesRepository(db)
        val draft = WordCard(language = "es-ES", word = "perro", reading = "", translation = "собака",
            example = "Tengo un perro.", exampleTranslation = "У меня есть собака.", successfulReviews = 3,
            intervalDays = 7, nextReviewAt = 1234, introducedAt = 100, writingSuccessfulReviews = 2, writingIntervalDays = 3)
        card = draft.copy(id = db.cardDao().insertOne(draft))
        Unit
    }
    @After fun close() { db.close() }

    @Test fun addsToExistingTrainerKeepsLegacyExampleProgressAndBackup() = runBlocking {
        val row = row("El perro duerme.")
        save(row)
        assertEquals(1, repo.observe(card).first().size)
        assertTrue(repo.add(card, row.second.id))
        val examples = db.cardDao().examplesFor(card.id)
        assertEquals(setOf(card.example, "El perro duerme."), examples.map { it.example }.toSet())
        assertEquals("", examples.first { it.example != card.example }.exampleTranslation)
        assertEquals(card, db.cardDao().getById(card.id))
        assertEquals(1, db.cardDao().allCards().size)
        assertEquals(examples[1], ExampleSelection.choose(card.copy(successfulReviews = 1), ReviewMode.RECOGNITION, examples))
        val copy = BackupFormat.decode(BackupFormat.encode(Backup(1, listOf(card), examples, emptyList())))
        assertEquals(examples.map { it.copy(id = 0) }, copy.examples)
        db.wordHistoryDao().clearAll()
        assertEquals(examples, db.cardDao().examplesFor(card.id))
    }
    @Test fun concurrentClicksAndRepeatedSourcesDoNotDuplicateExamples() = runBlocking {
        val first = row("El perro duerme.")
        val second = row("El perro duerme.", seed = 'b')
        save(first); save(second)
        val results = coroutineScope { (1..8).map { async(Dispatchers.Default) { repo.add(card, first.second.id) } }.awaitAll() }
        assertEquals(1, results.count { it })
        assertFalse(repo.add(card, second.second.id))
        assertEquals(2, db.cardDao().examplesFor(card.id).size)
    }
    @Test fun deletedHistoryDeletedCardsAndChangedMeaningsCannotBeSaved() = runBlocking {
        val row = row("El perro duerme.")
        save(row)
        db.cardDao().update(card.copy(translation = "пёс"))
        assertTrue(runCatching { repo.add(card, row.second.id) }.isFailure)
        assertTrue(db.cardDao().examplesFor(card.id).isEmpty())
        db.cardDao().update(card)
        db.wordHistoryDao().clearAll()
        assertTrue(runCatching { repo.add(card, row.second.id) }.isFailure)
        save(row); db.cardDao().delete(card.id)
        assertTrue(runCatching { repo.add(card, row.second.id) }.isFailure)
    }
    @Test fun differentWordsLanguagesAndCardsStayIsolated() = runBlocking {
        val wrong = row("The perro sleeps.", language = "en-US")
        save(wrong)
        assertTrue(repo.observe(card).first().isEmpty())
        assertTrue(runCatching { repo.add(card, wrong.second.id) }.isFailure)
        val right = row("El perro duerme.", seed = 'b')
        save(right)
        val other = card.copy(id = 0, translation = "пёс")
        val otherId = db.cardDao().insertOne(other)
        assertTrue(repo.add(card, right.second.id))
        assertTrue(db.cardDao().examplesFor(otherId).isEmpty())
        assertEquals(2, db.cardDao().allCards().size)
    }
    @Test fun suggestionsDeduplicateSentencesExcludeSavedExamplesAndBoundTheList() {
        val rows = listOf(study(row(card.example)), study(row("El perro duerme.")), study(row("El perro duerme.", seed = 'b')))
        val picks = ReadingExamples.suggestions(card, rows, emptyList())
        assertEquals(1, picks.size)
        assertTrue(picks.single().source.contains("Википедия"))
        assertEquals("Википедия · Статья", ReadingExamples.suggestions(card,
            listOf(study(row("El perro duerme.")).copy(chapterTitle = "Статья")), emptyList()).single().source)
        assertTrue(ReadingExamples.suggestions(card, rows,
            listOf(CardExample(cardId = card.id, example = "El  perro duerme.", exampleTranslation = ""))).isEmpty())
        val many = (1..10).map { study(row("El perro duerme $it.", seed = ('a'.code + it).toChar())) }
        assertEquals(5, ReadingExamples.suggestions(card, many, emptyList()).size)
    }
    @Test fun inflectedSpanishEnglishAndChineseContextsUseTheirRecordedLemma() {
        for ((language, lemma, original, text) in listOf(
            listOf("es-ES", "hablar", "habló", "Ayer habló con su madre."),
            listOf("en-US", "teacher", "teachers", "The teachers are here."),
            listOf("zh-CN", "图书馆", "图书馆", "他去了图书馆。")
        )) {
            val target = card.copy(language = language, word = lemma, example = "")
            val candidate = study(row(text, original, lemma, language))
            assertEquals(text, ReadingExamples.suggestions(target, listOf(candidate), emptyList()).single().sentence)
        }
    }
}
