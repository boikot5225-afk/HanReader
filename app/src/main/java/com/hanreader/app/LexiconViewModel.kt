package com.hanreader.app

import android.app.Application
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
/** HanReader teaches Chinese only. The language column stays in the schema so Lexicon backups restore as-is. */
val languages = listOf(
    Language("zh-CN", "中文")
)

@OptIn(ExperimentalCoroutinesApi::class)
class LexiconViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = LexiconDatabase.get(app).cardDao()
    private val knownDao = LexiconDatabase.get(app).knownWordDao()
    private val selection = MutableStateFlow("zh-CN")
    private val settingsStore = LearningSettingsStore(app)
    private val preferences = MutableStateFlow(settingsStore.load("zh-CN"))
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
                val backup = Backup(
                    exportedAt = System.currentTimeMillis(),
                    cards = cards,
                    examples = dao.allExamples(),
                    settings = languages.map { settingsStore.load(it.tag) },
                    knownWords = knownDao.all()
                )
                val text = BackupFormat.encode(backup)
                withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Не удалось открыть файл для записи")
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
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
                val text = withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openInputStream(uri)
                        ?: error("Не удалось открыть файл")
                    stream.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                BackupFormat.decode(text)
            })
        }
    }

    /** Replaces all words, progress and settings with the backup. */
    fun restoreBackup(backup: Backup, onDone: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onDone(runCatching {
                // One transaction: a failure leaves cards and known words exactly as before.
                LexiconDatabase.get(getApplication()).withTransaction {
                    dao.replaceAll(backup.cards, backup.examples)
                    backup.knownWords?.let { knownDao.replaceAll(it) }
                }
                backup.settings
                    .filter { s -> languages.any { it.tag == s.language } }
                    .forEach { settingsStore.save(it) }
                preferences.value = settingsStore.load(selection.value)
            })
        }
    }

    fun markAlreadyKnown(card: WordCard, mode: ReviewMode) {
        viewModelScope.launch {
            dao.update(ReviewSchedule.markAlreadyKnown(card, mode, System.currentTimeMillis()))
        }
    }

    fun rate(card: WordCard, remembered: Boolean, mode: ReviewMode) {
        viewModelScope.launch {
            dao.update(ReviewSchedule.rate(card, remembered, System.currentTimeMillis(), mode))
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { dao.delete(id) }
    }
}
