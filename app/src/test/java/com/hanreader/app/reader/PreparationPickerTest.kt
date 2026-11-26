package com.hanreader.app.reader

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h1000dp-xhdpi")
class PreparationPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun forecast(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun selectionUpdatesForecastAndAddingCardsDoesNotMarkWordsKnown() {
        val words = listOf(StudyCandidate("hablar", 3, "", "говорить", 0),
            StudyCandidate("perro", 2, "", "собака", 0))
        val plan = ChapterPreparation(KnownCoverage(10, 5, 2), mapOf("hablar" to 3, "perro" to 2))
        var marked = false
        var added: List<StudyCandidate>? = null
        val candidates = mutableStateOf(words)
        val loading = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CandidatePicker(candidates.value, "В разделе", false, null,
                    onKnown = { marked = true }, onIgnore = {}, onAdd = { added = it },
                    preview = { PreparationForecast(plan, it) },
                    sentenceFor = { if (it.word == "hablar") "Hablo y hablamos." else null }, loading = loading.value)
            }
        }
        compose.onNodeWithText("Знакомо сейчас: 50% слов раздела").assertExists()
        forecast("Если выучить выбранные слова (2): 100%")
        compose.onNodeWithText("Hablo y hablamos.").assertExists()
        compose.onAllNodes(isToggleable())[0].performClick()
        forecast("Если выучить выбранные слова (1): 70%")
        // Background recalculation must not re-check words the reader deselected.
        compose.runOnIdle { candidates.value = emptyList(); loading.value = true }
        compose.onNodeWithText("Незнакомых слов для изучения не осталось.").assertDoesNotExist()
        compose.runOnIdle { candidates.value = words; loading.value = false }
        forecast("Если выучить выбранные слова (1): 70%")
        compose.onNodeWithText("Добавить в карточки: 1").performClick()
        compose.runOnIdle {
            assertEquals(listOf("perro"), added!!.map { it.word })
            assertFalse(marked)
        }
    }
}
