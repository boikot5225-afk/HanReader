package com.hanreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private val card = WordCard(
        id = 7,
        language = "zh-CN",
        word = "终于",
        reading = "zhōngyú",
        translation = "наконец",
        example = "他终于回来了。",
        exampleTranslation = "Он наконец вернулся.",
        createdAt = 1_000L,
        nextReviewAt = 5_000L,
        successfulReviews = 3,
        intervalDays = 7,
        recognitionLapses = 2,
        writingNextReviewAt = 6_000L,
        writingSuccessfulReviews = 1,
        writingIntervalDays = 1,
        writingLapses = 1,
        introducedAt = 2_000L
    )
    private val examples = listOf(
        CardExample(cardId = 7, example = "他终于回来了。", exampleTranslation = "Он наконец вернулся."),
        CardExample(cardId = 7, example = "终于下雨了。", exampleTranslation = "Наконец пошёл дождь.")
    )
    private val settings = listOf(LearningSettings("zh-CN", 20, AutoSpeechMode.BOTH, 0.8f))

    @Test fun roundTripKeepsProgressExamplesAndSettings() {
        val restored = BackupFormat.decode(BackupFormat.encode(Backup(42L, listOf(card), examples, settings)))
        assertEquals(42L, restored.exportedAt)
        assertEquals(listOf(card), restored.cards)
        assertEquals(examples, restored.examples)
        assertEquals(1, restored.settings.size)
        assertEquals(20, restored.settings[0].dailyNewWords)
        assertEquals(AutoSpeechMode.BOTH, restored.settings[0].autoSpeech)
        assertEquals(0.8f, restored.settings[0].speechRate, 0.001f)
    }

    @Test fun rejectsForeignFiles() {
        val notJson = assertThrows(BackupFormatException::class.java) { BackupFormat.decode("hello") }
        assertTrue(notJson.message!!.contains("не файл"))
        assertThrows(BackupFormatException::class.java) { BackupFormat.decode("{\"cards\":[]}") }
    }

    @Test fun rejectsNewerVersion() {
        val json = BackupFormat.encode(Backup(1L, listOf(card), examples, settings))
            .replace("\"version\": 1", "\"version\": 99")
        val error = assertThrows(BackupFormatException::class.java) { BackupFormat.decode(json) }
        assertTrue(error.message!!.contains("новой версией"))
    }

    @Test fun rejectsExamplesWithoutWord() {
        val json = BackupFormat.encode(
            Backup(1L, listOf(card), examples + CardExample(cardId = 99, example = "x", exampleTranslation = "y"), settings)
        )
        assertThrows(BackupFormatException::class.java) { BackupFormat.decode(json) }
    }

    @Test fun rejectsDuplicateWords() {
        val json = BackupFormat.encode(Backup(1L, listOf(card, card.copy(id = 8)), emptyList(), emptyList()))
        assertThrows(BackupFormatException::class.java) { BackupFormat.decode(json) }
    }
}
