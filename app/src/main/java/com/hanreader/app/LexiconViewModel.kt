package com.hanreader.app

import android.app.Application
import java.io.File
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.room.withTransaction
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Language(val tag: String, val title: String)
/** The cards of the language being read ([com.hanreader.app.lang.Languages.current]); other languages' cards stay stored. */
val languages: List<Language> get() = listOf(com.hanreader.app.lang.Languages.current.let { Language(it.tag, it.title) })

@OptIn(ExperimentalCoroutinesApi::class)
class LexiconViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = LexiconDatabase.get(app).cardDao()
    private val knownDao = LexiconDatabase.get(app).knownWordDao()
    private val booksDir = File(app.filesDir, "books").apply { mkdirs() }
    private val selection = MutableStateFlow(com.hanreader.app.lang.Languages.current.tag)
    private val settingsStore = LearningSettingsStore(app)
    private val preferences = MutableStateFlow(settingsStore.load(com.hanreader.app.lang.Languages.current.tag))
    val learningSettings: StateFlow<LearningSettings> = preferences.asStateFlow()
    val language: StateFlow<String> = selection.asStateFlow()
    val contexts: StateFlow<List<CardExample>> = selection.flatMapLatest {
        dao.observeExamples(it)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val cards: StateFlow<List<WordCard>> = selection.flatMapLatest {
        dao.observeByLanguage(it)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectLanguage(value: String) {
        if (languages.any { it.tag == value }) {
            preferences.value = settingsStore.load(value)
            selection.value = value
        }
    }

    fun setDailyLimit(value: Int) {
        if (value in LearningSettingsStore.DAILY_LIMITS) {
            updateSettings { it.copy(dailyNewWords = value) }
        }
    }

    fun setAutoSpeech(value: AutoSpeechMode) {
        updateSettings { it.copy(autoSpeech = value) }
    }

    fun setSpeechRate(value: Float) {
        val rounded = kotlin.math.round(value.coerceIn(0.7f, 1.3f) * 10f) / 10f
        updateSettings { it.copy(speechRate = rounded) }
    }

    private fun updateSettings(transform: (LearningSettings) -> LearningSettings) {
        val next = transform(preferences.value)
        preferences.value = next
        settingsStore.save(next)
    }

    fun import(rows: List<ImportCard>, onDone: (Result<ImportStats>) -> Unit) {
        val currentLanguage = selection.value
        viewModelScope.launch {
            onDone(runCatching { dao.importCards(currentLanguage, rows) })
        }
    }

    fun addContext(
        cardId: Long,
        example: String,
        translation: String,
        onDone: (Result<Boolean>) -> Unit
    ) {
        viewModelScope.launch {
            onDone(runCatching { dao.addContext(cardId, example, translation) })
        }
    }

    /** onDone(false): another card already has this word and translation. */
    fun editCard(
        card: WordCard,
        word: String,
        reading: String,
        translation: String,
        onDone: (Result<Boolean>) -> Unit
    ) {
        viewModelScope.launch {
            onDone(runCatching { dao.editCard(card.id, card.language, word, reading, translation) })
        }
    }

    /** exampleId 0 = the legacy example kept only on the card. onDone(false): duplicate. */
    fun editExample(
        cardId: Long,
        exampleId: Long,
        example: String,
        translation: String,
        onDone: (Result<Boolean>) -> Unit
    ) {
        viewModelScope.launch {
            onDone(runCatching { dao.editExample(cardId, exampleId, example, translation) })
        }
    }

    /** onDone(false): the last example of a word cannot be removed. */
    fun deleteExample(cardId: Long, exampleId: Long, onDone: (Result<Boolean>) -> Unit) {
        viewModelScope.launch {
            onDone(runCatching { dao.deleteExample(cardId, exampleId) })
        }
    }

    /** Writes every language to the file chosen by the user. onDone gets the number of words. */
    fun exportBackup(uri: Uri, onDone: (Result<Int>) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            onDone(runCatching {
                val cards = dao.allCards()
                val db = LexiconDatabase.get(app)
                val backup = Backup(
                    exportedAt = System.currentTimeMillis(),
                    cards = cards,
                    examples = dao.allExamples(),
                    settings = com.hanreader.app.lang.Languages.ALL.map { settingsStore.load(it.tag) },
                    knownWords = knownDao.all(),
                    userNames = db.userNameDao().all().map { it.word },
                    sources = dao.allSources(),
                    library = LibraryBackup(
                        books = db.bookDao().all(),
                        bookmarks = db.bookmarkDao().all(),
                        highlights = db.highlightDao().all(),
                        ignored = db.bookDao().allIgnored(),
                        progress = db.statsDao().allProgress(),
                        languages = db.bookDao().all().associate { it.id to com.hanreader.app.lang.BookLanguages.of(getApplication(), it.id) }
                            .filterValues { it != com.hanreader.app.lang.Languages.CHINESE.tag }
                    ),
                    userMeanings = db.userMeaningDao().all(),
                    userSplits = db.userSplitDao().all().map { it.word },
                    activity = db.statsDao().allDays()
                )
                withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Не удалось открыть файл для записи")
                    stream.use { BackupArchive.write(it, backup, booksDir) }
                }
                cards.size
            })
        }
    }

    /** Reads and validates a backup without changing anything. */
    fun readBackup(uri: Uri, onDone: (Result<Backup>) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            onDone(runCatching {
                withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openInputStream(uri)
                        ?: error("Не удалось открыть файл")
                    stream.use { BackupArchive.read(it, File(app.cacheDir, "restore")) }
                }
            })
        }
    }

    /** Replaces all words, progress and settings with the backup. */
    fun restoreBackup(backup: Backup, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onDone(runCatching {
                val db = LexiconDatabase.get(getApplication())
                // Book files first, under their own names (a book restored onto the same phone is the same file).
                backup.library?.let { _ ->
                    withContext(Dispatchers.IO) {
                        for ((name, f) in backup.bookFiles) {
                            val target = File(booksDir, name)
                            if (!f.renameTo(target)) f.copyTo(target, overwrite = true)
                        }
                    }
                }
                // One transaction: a failure leaves cards, known words and books exactly as before.
                db.withTransaction {
                    dao.replaceAll(backup.cards, backup.examples)
                    backup.sources?.let { dao.insertSourcesStrict(it) }
                    backup.library?.let { lib ->
                        val books = db.bookDao()
                        books.deleteAll()
                        // Word counts are rebuilt by the next analysis; «Не учить» marks survive it.
                        books.insertAllStrict(lib.books.map { it.copy(analyzedTokens = 0) })
                        db.bookmarkDao().insertAllStrict(lib.bookmarks)
                        db.highlightDao().insertAllStrict(lib.highlights)
                        lib.ignored.chunked(2_000).forEach { books.insertWords(it) }
                        db.statsDao().putProgressAll(lib.progress)
                    }
                    // The language of each restored book (Chinese when the copy says nothing).
                    backup.library?.let { lib -> com.hanreader.app.lang.BookLanguages.replaceAll(getApplication(), lib.languages) }
                    backup.userSplits?.let { list ->
                        db.userSplitDao().removeAll()
                        db.userSplitDao().putAll(list.map { com.hanreader.app.UserSplit(it) })
                    }
                    backup.userMeanings?.let { list ->
                        db.userMeaningDao().removeAll()
                        db.userMeaningDao().putAll(list)
                    }
                    backup.activity?.let { days ->
                        db.statsDao().clearDays()
                        db.statsDao().putDays(days)
                    }
                    backup.knownWords?.let { knownDao.replaceAll(it) }
                    backup.userNames?.let { names ->
                        val dao = LexiconDatabase.get(getApplication()).userNameDao()
                        dao.removeAll()
                        dao.putAll(names.map { UserName(it) })
                    }
                }
                backup.library?.let { lib ->
                    withContext(Dispatchers.IO) {
                        val keep = lib.books.mapTo(HashSet()) { it.fileName }
                        booksDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
                        // Covers are made again from the books (ids may now name other books).
                        File(getApplication<Application>().filesDir, "covers").listFiles()?.forEach { it.delete() }
                        File(getApplication<Application>().cacheDir, "restore").deleteRecursively()
                    }
                }
                backup.settings
                    .filter { s -> com.hanreader.app.lang.Languages.ALL.any { it.tag == s.language } }
                    .forEach { settingsStore.save(it) }
                preferences.value = settingsStore.load(selection.value)
            })
        }
    }

    private val statsDao = LexiconDatabase.get(app).statsDao()

    fun markAlreadyKnown(card: WordCard, mode: ReviewMode) {
        viewModelScope.launch {
            dao.update(ReviewSchedule.markAlreadyKnown(card, mode, System.currentTimeMillis()))
            statsDao.addReview(com.hanreader.app.lang.LanguageDays.key(com.hanreader.app.reader.ReadingStats.today()))
        }
    }

    fun rate(card: WordCard, remembered: Boolean, mode: ReviewMode) {
        viewModelScope.launch {
            dao.update(ReviewSchedule.rate(card, remembered, System.currentTimeMillis(), mode))
            statsDao.addReview(com.hanreader.app.lang.LanguageDays.key(com.hanreader.app.reader.ReadingStats.today()))
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { dao.delete(id) }
    }
}
