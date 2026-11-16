package com.hanreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private val aiBook = BookEntity(id = 7, title = "Test", author = "", fileName = "test.epub", chapterCount = 1)
    private val aiLibrary = LibraryBackup(listOf(aiBook), emptyList(), emptyList(), emptyList())
    private val aiCheck = com.hanreader.app.ai.AiCheck("a".repeat(64), 7, 0, 0, "b".repeat(64),
        2, 7, 2, 7, "pista", "дорожка", "улика", "Контекст расследования", "high", "accepted", 42)

    @Test fun allAiStatesAndCacheIdentitiesSurviveBackup() {
        val rows = listOf("accepted", "proposed", "rejected", "checked").mapIndexed { i, state ->
            aiCheck.copy(cacheKey = i.toString().repeat(64), state = state,
                translation = if (state == "checked") null else "улика")
        }
        val json = BackupFormat.encode(Backup(1, emptyList(), emptyList(), emptyList(), library = aiLibrary, aiChecks = rows))
        assertEquals(rows, BackupFormat.decode(json).aiChecks)
        assertTrue(!json.contains("apiKey") && !json.contains("mock-key"))
    }

    @Test fun oldBackupsHaveNoAiSectionAndInvalidChecksAreRejected() {
        assertEquals(null, BackupFormat.decode(BackupFormat.encode(Backup(1, emptyList(), emptyList(), emptyList()))).aiChecks)
        val valid = BackupFormat.encode(Backup(1, emptyList(), emptyList(), emptyList(), library = aiLibrary, aiChecks = listOf(aiCheck)))
        for (field in listOf("state", "bookId", "termEnd", "cacheKey")) {
            val root = org.json.JSONObject(valid)
            root.getJSONArray("aiChecks").getJSONObject(0).put(field, when (field) {
                "bookId" -> 999; "termEnd" -> 1; else -> "invalid"
            })
            assertThrows(BackupFormatException::class.java) { BackupFormat.decode(root.toString()) }
        }
        val root = org.json.JSONObject(valid)
        root.getJSONArray("aiChecks").put(root.getJSONArray("aiChecks").getJSONObject(0))
        assertThrows(BackupFormatException::class.java) { BackupFormat.decode(root.toString()) }
        root.remove("library")
        assertThrows(BackupFormatException::class.java) { BackupFormat.decode(root.toString()) }
    }
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
            .replace("\"version\": ${BackupFormat.VERSION}", "\"version\": 99")
        val error = assertThrows(BackupFormatException::class.java) { BackupFormat.decode(json) }
        assertTrue(error.message!!.contains("новой версией"))
    }

    /** A version 1 file (words only) still restores and leaves the library alone. */
    @Test fun readsVersionOne() {
        val json = BackupFormat.encode(Backup(1L, listOf(card), examples, settings))
            .replace("\"version\": ${BackupFormat.VERSION}", "\"version\": 1")
        val b = BackupFormat.decode(json)
        assertEquals(1, b.cards.size)
        assertEquals(null, b.library)
        assertEquals(null, b.sources)
    }

    @Test fun rejectsUnsafeBookNames() {
        val book = BookEntity(id = 3, title = "x", author = "", fileName = "../evil", chapterCount = 1)
        val json = BackupFormat.encode(Backup(1L, listOf(card), examples, settings, library = LibraryBackup(listOf(book), emptyList(), emptyList(), emptyList())))
        assertThrows(BackupFormatException::class.java) { BackupFormat.decode(json) }
    }

    @Test fun keepsBookLanguages() {
        val zh = BookEntity(id = 3, title = "雨夜", author = "", fileName = "book-1.txt", chapterCount = 1)
        val es = BookEntity(id = 4, title = "El narco", author = "", fileName = "book-2.epub", chapterCount = 1)
        val lib = LibraryBackup(listOf(zh, es), emptyList(), emptyList(), emptyList(), languages = mapOf(4L to "es-ES"))
        val back = BackupFormat.decode(BackupFormat.encode(Backup(1L, listOf(card), examples, settings, library = lib)))
        assertEquals(mapOf(4L to "es-ES"), back.library!!.languages)
    }

    @Test fun keepsOwnMeanings() {
        val json = BackupFormat.encode(Backup(1L, listOf(card), examples, settings, userMeanings = listOf(UserMeaning("端", "держать в руках", 7))))
        assertEquals(listOf(UserMeaning("端", "держать в руках", 7)), BackupFormat.decode(json).userMeanings)
    }

    @Test fun keepsSplits() {
        val json = BackupFormat.encode(Backup(1L, listOf(card), examples, settings, userSplits = listOf("把头")))
        assertEquals(listOf("把头"), BackupFormat.decode(json).userSplits)
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
