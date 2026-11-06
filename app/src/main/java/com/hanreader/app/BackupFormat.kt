package com.hanreader.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Everything a backup restores: words with SRS progress, their examples and per-language settings. */
data class Backup(
    val exportedAt: Long,
    val cards: List<WordCard>,
    val examples: List<CardExample>,
    val settings: List<LearningSettings>,
    /** HanReader: known words. Null when the file has none (Lexicon backups): restoring keeps the current ones. */
    val knownWords: List<KnownWord>? = null,
    /** HanReader: words marked «Это имя». Null when the file has none: restoring keeps the current ones. */
    val userNames: List<String>? = null
)

class BackupFormatException(message: String) : Exception(message)

/**
 * Versioned JSON backup. The file is fully parsed and validated before
 * anything in the database is touched.
 */
object BackupFormat {
    private const val FORMAT = "lexicon-backup"
    const val VERSION = 1

    fun encode(backup: Backup): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", backup.exportedAt)
        root.put("cards", JSONArray().apply {
            backup.cards.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("language", c.language)
                    put("word", c.word)
                    put("reading", c.reading)
                    put("translation", c.translation)
                    put("example", c.example)
                    put("exampleTranslation", c.exampleTranslation)
                    put("createdAt", c.createdAt)
                    put("introducedAt", c.introducedAt)
                    put("nextReviewAt", c.nextReviewAt)
                    put("successfulReviews", c.successfulReviews)
                    put("intervalDays", c.intervalDays)
                    put("recognitionLapses", c.recognitionLapses)
                    put("writingNextReviewAt", c.writingNextReviewAt)
                    put("writingSuccessfulReviews", c.writingSuccessfulReviews)
                    put("writingIntervalDays", c.writingIntervalDays)
                    put("writingLapses", c.writingLapses)
                })
            }
        })
        root.put("examples", JSONArray().apply {
            backup.examples.forEach { e ->
                put(JSONObject().apply {
                    put("cardId", e.cardId)
                    put("example", e.example)
                    put("exampleTranslation", e.exampleTranslation)
                })
            }
        })
        root.put("settings", JSONArray().apply {
            backup.settings.forEach { s ->
                put(JSONObject().apply {
                    put("language", s.language)
                    put("dailyNewWords", s.dailyNewWords)
                    put("autoSpeech", s.autoSpeech.name)
                    put("speechRate", s.speechRate.toDouble())
                })
            }
        })
        backup.knownWords?.let { known ->
            root.put("knownWords", JSONArray().apply {
                known.forEach { k ->
                    put(JSONObject().apply {
                        put("word", k.word)
                        put("source", k.source)
                        put("addedAt", k.addedAt)
                    })
                }
            })
        }
        backup.userNames?.let { names -> root.put("userNames", JSONArray().apply { names.forEach { put(it) } }) }
        return root.toString(2)
    }

    fun decode(text: String): Backup {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupFormatException("Это не файл резервной копии Lexicon.")
        }
        if (root.optString("format") != FORMAT) {
            throw BackupFormatException("Это не файл резервной копии Lexicon.")
        }
        val version = root.optInt("version", -1)
        if (version < 1 || version > VERSION) {
            throw BackupFormatException("Копия создана более новой версией Lexicon. Обнови приложение.")
        }
        try {
            val cardsJson = root.getJSONArray("cards")
            val cards = (0 until cardsJson.length()).map { i ->
                val c = cardsJson.getJSONObject(i)
                WordCard(
                    id = c.getLong("id"),
                    language = c.getString("language"),
                    word = c.getString("word"),
                    reading = c.optString("reading", ""),
                    translation = c.getString("translation"),
                    example = c.optString("example", ""),
                    exampleTranslation = c.optString("exampleTranslation", ""),
                    createdAt = c.optLong("createdAt", 0L),
                    introducedAt = c.optLong("introducedAt", 0L),
                    nextReviewAt = c.optLong("nextReviewAt", 0L),
                    successfulReviews = c.optInt("successfulReviews", 0),
                    intervalDays = c.optInt("intervalDays", 0),
                    recognitionLapses = c.optInt("recognitionLapses", 0),
                    writingNextReviewAt = c.optLong("writingNextReviewAt", 0L),
                    writingSuccessfulReviews = c.optInt("writingSuccessfulReviews", 0),
                    writingIntervalDays = c.optInt("writingIntervalDays", 0),
                    writingLapses = c.optInt("writingLapses", 0)
                )
            }
            val examplesJson = root.optJSONArray("examples") ?: JSONArray()
            val examples = (0 until examplesJson.length()).map { i ->
                val e = examplesJson.getJSONObject(i)
                CardExample(
                    cardId = e.getLong("cardId"),
                    example = e.getString("example"),
                    exampleTranslation = e.getString("exampleTranslation")
                )
            }
            val settingsJson = root.optJSONArray("settings") ?: JSONArray()
            val settings = (0 until settingsJson.length()).map { i ->
                val s = settingsJson.getJSONObject(i)
                val speech = s.optString("autoSpeech", AutoSpeechMode.OFF.name)
                LearningSettings(
                    language = s.getString("language"),
                    dailyNewWords = s.optInt("dailyNewWords", 10)
                        .takeIf { it in LearningSettingsStore.DAILY_LIMITS } ?: 10,
                    autoSpeech = AutoSpeechMode.entries.firstOrNull { it.name == speech } ?: AutoSpeechMode.OFF,
                    speechRate = s.optDouble("speechRate", 1.0).toFloat().coerceIn(0.7f, 1.3f)
                )
            }
            val knownJson = root.optJSONArray("knownWords")
            val known = knownJson?.let { arr ->
                (0 until arr.length()).map { i ->
                    val k = arr.getJSONObject(i)
                    KnownWord(k.getString("word"), k.optString("source", "manual"), k.optLong("addedAt", 0L))
                }.filter { it.word.isNotBlank() }.distinctBy { it.word }
            }
            val names = root.optJSONArray("userNames")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() }.distinct()
            }
            validate(cards, examples)
            return Backup(root.optLong("exportedAt", 0L), cards, examples, settings, known, names)
        } catch (e: JSONException) {
            throw BackupFormatException("Файл копии повреждён: " + (e.message ?: "неверная структура"))
        }
    }

    private fun validate(cards: List<WordCard>, examples: List<CardExample>) {
        val ids = HashSet<Long>()
        val keys = HashSet<Triple<String, String, String>>()
        cards.forEach { c ->
            if (c.id <= 0L || !ids.add(c.id)) throw BackupFormatException("Файл копии повреждён: повторяется слово #" + c.id)
            if (c.word.isBlank() || c.translation.isBlank()) {
                throw BackupFormatException("Файл копии повреждён: у слова #" + c.id + " нет текста или перевода")
            }
            if (!keys.add(Triple(c.language, c.word, c.translation))) {
                throw BackupFormatException("Файл копии повреждён: слово «" + c.word + "» записано дважды")
            }
        }
        val exampleKeys = HashSet<Triple<Long, String, String>>()
        examples.forEach { e ->
            if (e.cardId !in ids) throw BackupFormatException("Файл копии повреждён: пример без слова")
            if (!exampleKeys.add(Triple(e.cardId, e.example, e.exampleTranslation))) {
                throw BackupFormatException("Файл копии повреждён: пример повторяется")
            }
        }
    }
}
