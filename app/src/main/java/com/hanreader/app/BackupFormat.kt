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
    /** HanReader: words marked «Это имя», each with its language. Null when the file has none: restoring keeps the current ones. */
    val userNames: List<UserName>? = null,
    /** HanReader: where saved words were met. Null in older files: restoring keeps none (they belong to the cards). */
    val sources: List<WordSource>? = null,
    /** HanReader: the library. Null in older files and word-only copies: restoring keeps the current books. */
    val library: LibraryBackup? = null,
    /** HanReader: the reader's own meanings of words. Null in older files: restoring keeps the current ones. */
    val userMeanings: List<UserMeaning>? = null,
    /** HanReader: words the reader split. Null in older files: restoring keeps the current ones. */
    val userSplits: List<String>? = null,
    /** HanReader: reading time and reviews by day. Null in older files: restoring keeps the current ones. */
    val activity: List<DailyActivity>? = null,
    /** Book files unpacked from the archive (file name -> temporary file); not part of the JSON. */
    val bookFiles: Map<String, java.io.File> = emptyMap()
)

/** Books with their reading positions, bookmarks, highlights and «Не учить» marks. */
data class LibraryBackup(
    val books: List<BookEntity>,
    val bookmarks: List<Bookmark>,
    val highlights: List<Highlight>,
    val ignored: List<BookWord>,
    /** Known-word share of each book by day. */
    val progress: List<BookProgress> = emptyList(),
    /** Language of each book (book id -> tag) other than Chinese; a book missing here is Chinese. */
    val languages: Map<Long, String> = emptyMap()
)

class BackupFormatException(message: String) : Exception(message)

/**
 * Versioned JSON backup. The file is fully parsed and validated before
 * anything in the database is touched.
 */
object BackupFormat {
    private const val FORMAT = "lexicon-backup"
    /** 2: HanReader library, word sources. Version 1 files still restore. */
    /** 3 (0.24.2): known words, names and meanings carry their language. */
    const val VERSION = 3

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
                        put("language", k.language)
                    })
                }
            })
        }
        backup.userNames?.let { names ->
            root.put("userNames", JSONArray().apply {
                names.forEach { n -> put(JSONObject().apply { put("word", n.word); put("language", n.language); put("addedAt", n.addedAt) }) }
            })
        }
        backup.sources?.let { list ->
            root.put("sources", JSONArray().apply {
                list.forEach { w ->
                    put(JSONObject().apply {
                        put("cardId", w.cardId); put("bookId", w.bookId); put("bookTitle", w.bookTitle)
                        put("chapterIndex", w.chapterIndex); put("chapterTitle", w.chapterTitle)
                        put("sentence", w.sentence); put("createdAt", w.createdAt)
                    })
                }
            })
        }
        backup.library?.let { lib ->
            root.put("library", JSONObject().apply {
                put("books", JSONArray().apply {
                    lib.books.forEach { b ->
                        put(JSONObject().apply {
                            put("id", b.id); put("title", b.title); put("author", b.author); put("fileName", b.fileName)
                            put("chapterCount", b.chapterCount); put("addedAt", b.addedAt); put("lastOpenedAt", b.lastOpenedAt)
                            put("chapterIndex", b.chapterIndex); put("blockIndex", b.blockIndex); put("charOffset", b.charOffset)
                            lib.languages[b.id]?.let { put("language", it) }
                        })
                    }
                })
                put("bookmarks", JSONArray().apply {
                    lib.bookmarks.forEach { m ->
                        put(JSONObject().apply {
                            put("bookId", m.bookId); put("chapterIndex", m.chapterIndex); put("blockIndex", m.blockIndex)
                            put("chapterTitle", m.chapterTitle); put("snippet", m.snippet); put("createdAt", m.createdAt)
                        })
                    }
                })
                put("highlights", JSONArray().apply {
                    lib.highlights.forEach { h ->
                        put(JSONObject().apply {
                            put("bookId", h.bookId); put("chapterIndex", h.chapterIndex); put("blockIndex", h.blockIndex)
                            put("startChar", h.startChar); put("endChar", h.endChar); put("color", h.color); put("note", h.note)
                            put("text", h.text); put("chapterTitle", h.chapterTitle); put("createdAt", h.createdAt)
                        })
                    }
                })
                put("ignored", JSONArray().apply {
                    lib.ignored.forEach { w -> put(JSONObject().apply { put("bookId", w.bookId); put("word", w.word) }) }
                })
                put("progress", JSONArray().apply {
                    lib.progress.forEach { r -> put(JSONObject().apply { put("bookId", r.bookId); put("day", r.day); put("percent", r.percent) }) }
                })
            })
        }
        backup.userMeanings?.let { list ->
            root.put("userMeanings", JSONArray().apply {
                list.forEach { m -> put(JSONObject().apply { put("word", m.word); put("meaning", m.meaning); put("updatedAt", m.updatedAt); put("language", m.language) }) }
            })
        }
        backup.userSplits?.let { list -> root.put("userSplits", JSONArray().apply { list.forEach { put(it) } }) }
        backup.activity?.let { list ->
            root.put("activity", JSONArray().apply {
                list.forEach { d ->
                    put(JSONObject().apply {
                        put("day", d.day); put("readMillis", d.readMillis); put("readChars", d.readChars)
                        put("pages", d.pages); put("reviews", d.reviews)
                    })
                }
            })
        }
        return root.toString(2)
    }

    fun decode(text: String): Backup {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupFormatException("Это не файл резервной копии ${BuildConfig.APP_NAME}.")
        }
        if (root.optString("format") != FORMAT) {
            throw BackupFormatException("Это не файл резервной копии ${BuildConfig.APP_NAME}.")
        }
        val version = root.optInt("version", -1)
        if (version < 1 || version > VERSION) {
            throw BackupFormatException("Копия создана более новой версией ${BuildConfig.APP_NAME}. Обнови приложение.")
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
                    val w = k.getString("word")
                    // Copies before version 3 have no language: Chinese characters or Spanish, as on update.
                    KnownWord(w, k.optString("source", "manual"), k.optLong("addedAt", 0L),
                        k.optString("language", "").ifEmpty { com.hanreader.app.lang.Languages.legacyOf(w) })
                }.filter { it.word.isNotBlank() }.distinctBy { it.language to it.word }
            }
            val names = root.optJSONArray("userNames")?.let { arr ->
                (0 until arr.length()).map { i ->
                    // Before version 3 a name is a plain string.
                    val o = arr.optJSONObject(i)
                    val w = (o?.getString("word") ?: arr.getString(i)).trim()
                    UserName(w, o?.optLong("addedAt", 0L) ?: 0L,
                        o?.optString("language", "")?.ifEmpty { null } ?: com.hanreader.app.lang.Languages.legacyOf(w))
                }.filter { it.word.isNotEmpty() }.distinctBy { it.language to it.word }
            }
            val sources = root.optJSONArray("sources")?.let { arr ->
                val ids = cards.mapTo(HashSet()) { it.id }
                (0 until arr.length()).map { i ->
                    val w = arr.getJSONObject(i)
                    WordSource(
                        cardId = w.getLong("cardId"), bookId = w.optLong("bookId", 0L), bookTitle = w.optString("bookTitle", ""),
                        chapterIndex = w.optInt("chapterIndex", 0), chapterTitle = w.optString("chapterTitle", ""),
                        sentence = w.optString("sentence", ""), createdAt = w.optLong("createdAt", 0L)
                    )
                }.filter { it.cardId in ids }
            }
            val library = root.optJSONObject("library")?.let { decodeLibrary(it) }
            val activity = root.optJSONArray("activity")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val d = arr.getJSONObject(i)
                    DailyActivity(d.getInt("day"), d.optLong("readMillis", 0L), d.optInt("readChars", 0), d.optInt("pages", 0), d.optInt("reviews", 0))
                }.filter { it.day in 19_000_101..29_991_231 }.distinctBy { it.day }
            }
            val meanings = root.optJSONArray("userMeanings")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    val w = m.getString("word")
                    val language = if (m.has("language")) m.getString("language")
                        else UserMeaning.languageOf(w, com.hanreader.app.lang.Languages.legacyOf(w))
                    UserMeaning(w, m.getString("meaning"), m.optLong("updatedAt", 0L), language)
                }.filter { it.word.isNotBlank() && it.meaning.isNotBlank() }.distinctBy { it.language to it.word }
            }
            val splits = root.optJSONArray("userSplits")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.length >= 2 }.distinct()
            }
            validate(cards, examples)
            return Backup(root.optLong("exportedAt", 0L), cards, examples, settings, known, names, sources, library, meanings, splits, activity)
        } catch (e: JSONException) {
            throw BackupFormatException("Файл копии повреждён: " + (e.message ?: "неверная структура"))
        }
    }

    private fun decodeLibrary(lib: JSONObject): LibraryBackup {
        fun arr(name: String) = lib.optJSONArray(name) ?: JSONArray()
        val booksJson = arr("books")
        val books = (0 until booksJson.length()).map { i ->
            val b = booksJson.getJSONObject(i)
            BookEntity(
                id = b.getLong("id"), title = b.getString("title"), author = b.optString("author", ""),
                fileName = b.getString("fileName"), chapterCount = b.optInt("chapterCount", 0),
                addedAt = b.optLong("addedAt", 0L), lastOpenedAt = b.optLong("lastOpenedAt", 0L),
                chapterIndex = b.optInt("chapterIndex", 0), blockIndex = b.optInt("blockIndex", 0),
                charOffset = b.optInt("charOffset", 0)
            )
        }
        val languages = (0 until booksJson.length()).mapNotNull { i ->
            val b = booksJson.getJSONObject(i)
            b.optString("language", "").takeIf { it.isNotEmpty() }?.let { b.getLong("id") to it }
        }.toMap()
        val ids = HashSet<Long>()
        val names = HashSet<String>()
        books.forEach { b ->
            if (b.id <= 0L || !ids.add(b.id)) throw BackupFormatException("Файл копии повреждён: повторяется книга #" + b.id)
            if (!safeName(b.fileName) || !names.add(b.fileName)) throw BackupFormatException("Файл копии повреждён: неверное имя файла книги")
        }
        val marksJson = arr("bookmarks")
        val bookmarks = (0 until marksJson.length()).map { i ->
            val m = marksJson.getJSONObject(i)
            Bookmark(
                bookId = m.getLong("bookId"), chapterIndex = m.getInt("chapterIndex"), blockIndex = m.getInt("blockIndex"),
                chapterTitle = m.optString("chapterTitle", ""), snippet = m.optString("snippet", ""), createdAt = m.optLong("createdAt", 0L)
            )
        }.filter { it.bookId in ids }
        val hlJson = arr("highlights")
        val highlights = (0 until hlJson.length()).map { i ->
            val h = hlJson.getJSONObject(i)
            Highlight(
                bookId = h.getLong("bookId"), chapterIndex = h.getInt("chapterIndex"), blockIndex = h.getInt("blockIndex"),
                startChar = h.getInt("startChar"), endChar = h.getInt("endChar"), color = h.optInt("color", 0),
                note = h.optString("note", ""), text = h.optString("text", ""), chapterTitle = h.optString("chapterTitle", ""),
                createdAt = h.optLong("createdAt", 0L)
            )
        }.filter { it.bookId in ids && it.endChar > it.startChar }
        val igJson = arr("ignored")
        val ignored = (0 until igJson.length()).map { i ->
            val w = igJson.getJSONObject(i)
            BookWord(w.getLong("bookId"), w.getString("word"), 0, 0, ignored = true)
        }.filter { it.bookId in ids && it.word.isNotBlank() }.distinctBy { it.bookId to it.word }
        val prJson = arr("progress")
        val progress = (0 until prJson.length()).map { i ->
            val r = prJson.getJSONObject(i)
            BookProgress(r.getLong("bookId"), r.getInt("day"), r.optInt("percent", 0).coerceIn(0, 100))
        }.filter { it.bookId in ids }.distinctBy { it.bookId to it.day }
        return LibraryBackup(books, bookmarks, highlights, ignored, progress, languages)
    }

    /** A plain file name: no folders, nothing that could leave the books folder. */
    fun safeName(name: String) = name.isNotBlank() && name.length < 200 && name.none { it == '/' || it == '\\' } && name != "." && name != ".."

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
