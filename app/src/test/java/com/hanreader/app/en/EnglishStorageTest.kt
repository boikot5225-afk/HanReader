package com.hanreader.app.en

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.Languages
import com.hanreader.app.lang.LanguageEngines
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnglishStorageTest {
    @Test fun loaderSelectsEnglishAndReviewHidesItsInflectedForms() = runBlocking {
        val before = Languages.current
        try {
            Languages.current = Languages.ENGLISH
            val engine = LanguageEngines.warmUp(ApplicationProvider.getApplicationContext<Context>()).await()
            assertTrue(engine is EnEngine)
            assertEquals("en-US", engine.info.tag)
            assertEquals("She ____ and ____.", WritingExercise.cloze("She went and goes.", "go", "en-US"))
            assertEquals("She ____ the word.", WritingExercise.cloze("She looked up the word.", "look up", "en-US"))
        } finally {
            EnEngine.release()
            Languages.current = before
        }
    }

    @Test fun sameSpellingAndTranslationInTwoLanguagesStaySeparate() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(app, LexiconDatabase::class.java).allowMainThreadQueries().build()
        try {
            for (language in listOf(Languages.ENGLISH.tag, Languages.SPANISH.tag)) {
                db.cardDao().insertOne(WordCard(language = language, word = "come", reading = "", translation = "значение", example = "", exampleTranslation = ""))
                db.knownWordDao().put(KnownWord("come", "manual", language = language))
                db.userMeaningDao().put(UserMeaning("red", if (language == Languages.ENGLISH.tag) "красный" else "сеть", language = language))
            }
            assertEquals(2, db.cardDao().allCards().size)
            assertEquals(setOf(Languages.ENGLISH.tag, Languages.SPANISH.tag), db.knownWordDao().all().map { it.language }.toSet())
            assertEquals(setOf("красный", "сеть"), db.userMeaningDao().all().map { it.meaning }.toSet())
        } finally { db.close() }
    }

    @Test fun englishBookWordsAndDayStatisticsRoundTripInTheExistingBackup() {
        val language = Languages.ENGLISH.tag
        val book = BookEntity(id = 1, title = "Sample", author = "", fileName = "sample.txt", chapterCount = 1, chapterIndex = 0)
        val original = Backup(1, listOf(WordCard(id = 1, language = language, word = "go", reading = "", translation = "идти", example = "She went.", exampleTranslation = "")),
            emptyList(), emptyList(), knownWords = listOf(KnownWord("go", "manual", language = language)),
            userMeanings = listOf(UserMeaning("red", "красный", language = language)),
            library = LibraryBackup(listOf(book), emptyList(), emptyList(), emptyList(), languages = mapOf(1L to language)),
            activity = listOf(DailyActivity(2_020_261_115, readMillis = 500),
                DailyActivity(1_020_261_115, readMillis = 200), DailyActivity(20261115, readMillis = 100)))
        val restored = BackupFormat.decode(BackupFormat.encode(original))
        assertEquals(language, restored.cards.single().language)
        assertEquals(language, restored.knownWords!!.single().language)
        assertEquals(language, restored.userMeanings!!.single().language)
        assertEquals(language, restored.library!!.languages[1])
        assertEquals(setOf(2_020_261_115, 1_020_261_115, 20261115), restored.activity!!.map { it.day }.toSet())
    }
}
