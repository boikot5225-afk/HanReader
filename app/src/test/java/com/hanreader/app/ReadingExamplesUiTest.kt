package com.hanreader.app

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.reader.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h1000dp-xhdpi")
class ReadingExamplesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db get() = LexiconDatabase.get(app)
    private lateinit var speaker: Speaker
    private lateinit var card: WordCard
    private val sentence = "El perro duerme."
    @Before fun setup(): Unit = runBlocking {
        db.wordHistoryDao().clearAll(); db.cardDao().replaceAll(emptyList(), emptyList())
        val draft = WordCard(language = "es-ES", word = "perro", reading = "", translation = "собака", example = "Tengo un perro.",
            exampleTranslation = "У меня есть собака.", introducedAt = 1, successfulReviews = 1)
        card = draft.copy(id = db.cardDao().insertOne(draft))
        val fragment = ReadingFragment("a".repeat(64), "wiki:es:42", null, "Artículo", "", 0, 0, 0, sentence)
        val word = WordEncounter(WordHistory.wordId(fragment.id, 3, 8, "perro"), fragment.id, "es-ES", "perro", "perro", 3, 8, 1)
        db.wordHistoryDao().record(EncounterBatch(listOf(fragment), listOf(word)))
        speaker = Speaker(app)
    }
    @After fun clean(): Unit = runBlocking {
        speaker.close(); db.wordHistoryDao().clearAll(); db.cardDao().replaceAll(emptyList(), emptyList())
    }
    private fun await(condition: () -> Boolean) { compose.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); condition() } }
    @Test fun recognitionShowsReadingContextOnlyAfterRevealAndAddingDoesNotChangeQuestion() {
        val vm = LexiconViewModel(app)
        val examples = mutableStateOf(emptyList<CardExample>())
        val cards = mutableStateOf(listOf(card))
        compose.setContent { LexiconTheme { ReviewSession(ReviewMode.RECOGNITION, cards.value, examples.value,
            card.language, speaker, vm, LearningSettings(card.language), System.currentTimeMillis(), {}, {}) } }
        compose.onNodeWithText(sentence).assertDoesNotExist()
        compose.onNodeWithText("Показать перевод").performClick()
        await { compose.onAllNodesWithText(sentence).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("В примеры карточки").performScrollTo().performClick()
        compose.onNodeWithText("Помню").assertIsDisplayed()
        await { runBlocking { db.cardDao().examplesFor(card.id).size == 2 } }
        compose.runOnIdle { examples.value = runBlocking { db.cardDao().examplesFor(card.id) } }
        compose.waitForIdle()
        compose.onNodeWithText(card.example).assertExists()
        assertEquals(card, runBlocking { db.cardDao().getById(card.id) })
        compose.runOnIdle { cards.value = listOf(card.copy(id = card.id + 1, word = "gato", example = "El gato duerme.", translation = "кот")) }
        compose.waitForIdle()
        compose.onNodeWithText(sentence).assertDoesNotExist()
        compose.onNodeWithText("Показать перевод").assertExists()
    }
    @Test fun writingDoesNotLeakReadingSentenceBeforeChecking() {
        val vm = LexiconViewModel(app)
        compose.setContent { LexiconTheme { ReviewSession(ReviewMode.WRITING, listOf(card), emptyList(),
            card.language, speaker, vm, LearningSettings(card.language), System.currentTimeMillis(), {}, {}) } }
        compose.onNodeWithText(sentence).assertDoesNotExist()
        compose.onNodeWithText("ВСТРЕЧАЛОСЬ В ЧТЕНИИ").assertDoesNotExist()
        compose.onNodeWithText("Твой ответ").performTextInput("perro")
        compose.onNodeWithText("Проверить").performClick()
        await { compose.onAllNodesWithText(sentence).fetchSemanticsNodes().isNotEmpty() }
    }
}
