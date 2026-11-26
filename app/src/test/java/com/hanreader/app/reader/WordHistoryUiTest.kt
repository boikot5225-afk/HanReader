package com.hanreader.app.reader

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.es.EsEngineTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h1000dp-xhdpi")
class WordHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val engine by lazy { EsEngineTest.load() }
    private lateinit var db: LexiconDatabase
    private val source = EncounterSource("book:1", "Книга", 1)
    private val text = "Hablo y hablamos."
    private val chapter by lazy { ChapterBuilder.build(TxtBook(text.toByteArray(), "Test"), 0, engine) }
    private fun stats() = runBlocking { db.wordHistoryDao().stats(engine.info.tag, "hablar").first() }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }
    @Before fun setup(): Unit = runBlocking {
        app.getSharedPreferences("word-history", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(app, LexiconDatabase::class.java).build()
        db.bookDao().insert(BookEntity(1, "Книга", "", "test.epub", 1))
        Unit
    }
    @After fun close() { db.close(); app.getSharedPreferences("word-history", 0).edit().clear().commit() }

    @Test fun automaticTrackingWaitsForReadingAndRevisitingDoesNotIncreaseCounts() {
        val active = mutableStateOf(false)
        val visible = mutableStateOf(listOf(WikiVisibleSpan(0, 0, text.length)))
        val current = mutableStateOf(chapter)
        compose.mainClock.autoAdvance = false
        compose.setContent { TrackWordEncounters(source, current.value, engine, visible.value, active.value, db.wordHistoryDao()) }
        compose.waitForIdle()
        compose.runOnIdle { active.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { active.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(0, stats().encounters); active.value = true }
        compose.mainClock.advanceTimeBy(1000)
        await { stats().encounters == 2 }
        compose.runOnIdle { visible.value = emptyList() }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            visible.value = listOf(WikiVisibleSpan(0, 0, text.length))
            current.value = chapter.copy(parseMs = 123) // rebuild without changing source text
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(WordHistoryStats(2, 0, 1), stats())
    }

    @Test fun lookupCountsOpeningsNotRecompositionsAndCanBeDisabled() {
        val selected = mutableStateOf(true)
        val unrelated = mutableStateOf(0)
        compose.setContent {
            Text("Recomposition ${unrelated.value}")
            if (selected.value) RecordWordLookup(source, chapter, engine, WikiVisibleSpan(0, 0, 5), db.wordHistoryDao())
        }
        await { stats().lookups == 1 }
        compose.runOnIdle { unrelated.value++ }
        compose.waitForIdle()
        assertEquals(1, stats().lookups)
        compose.runOnIdle { selected.value = false }
        compose.waitForIdle()
        compose.runOnIdle { selected.value = true }
        compose.waitForIdle()
        await { stats().lookups == 2 }
        compose.runOnIdle {
            selected.value = false
            app.getSharedPreferences("word-history", 0).edit().putBoolean("enabled", false).commit()
        }
        compose.waitForIdle()
        compose.runOnIdle { selected.value = true }
        compose.waitForIdle()
        assertEquals(WordHistoryStats(1, 2, 1), stats())
    }

    @Test fun historyPanelDisplaysSourceAndClearsOnlyAfterConfirmation() {
        runBlocking { db.wordHistoryDao().record(WordHistory.batch(source, chapter,
            listOf(WikiVisibleSpan(0, 0, text.length)), engine)) }
        compose.setContent { MaterialTheme { Column { WordHistoryPanel(engine.info.tag, "hablar", db.wordHistoryDao()) } } }
        compose.onNodeWithText("История слова").performClick()
        await { compose.onAllNodesWithText("Встреч: 2 · обращений к словарю: 0 · источников: 1").fetchSemanticsNodes().isNotEmpty() }
        await { compose.onAllNodesWithText("Книга", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Удалить историю этого слова").performClick()
        assertEquals(2, stats().encounters)
        compose.onNodeWithText("Отмена").performClick()
        assertEquals(2, stats().encounters)
        compose.onNodeWithText("Удалить историю этого слова").performClick()
        compose.onNodeWithText("Удалить").performClick()
        await { stats().encounters == 0 }
        assertEquals(1, runBlocking { db.wordHistoryDao().stats(engine.info.tag, "y").first() }.encounters)
    }
}
