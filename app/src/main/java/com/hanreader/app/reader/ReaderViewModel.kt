package com.hanreader.app.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanreader.app.BookEntity
import com.hanreader.app.KnownWord
import com.hanreader.app.isLearned
import com.hanreader.app.LexiconDatabase
import com.hanreader.app.ReaderSave
import com.hanreader.app.epub.EpubBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

const val STUDY_LANGUAGE = "zh-CN"

/** Which words get pinyin: all, only words not yet known, or none. */
enum class PinyinMode(val title: String) {
    ALL("Над всеми словами"),
    UNKNOWN("Только над незнакомыми"),
    OFF("Выключен")
}

/** The level the user marked as known in one tap; level 0 = none. */
data class LevelMark(val system: HskSystem, val level: Int)

/** An open book: the EPUB file, its row in the database and the chapter on screen. */
data class OpenBook(
    val entity: BookEntity,
    val epub: EpubBook,
    val chapter: ChapterContent? = null,
    val loading: Boolean = true,
    val error: String? = null
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val db = LexiconDatabase.get(app)
    private val books = db.bookDao()
    private val cards = db.cardDao()
    private val knownDao = db.knownWordDao()
    private val prefs = app.getSharedPreferences("reader_settings", android.content.Context.MODE_PRIVATE)
    private val engineDeferred = ZhEngine.warmUp(app)
    private val booksDir = File(app.filesDir, "books").apply { mkdirs() }
    private val openMutex = Mutex()
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val library: StateFlow<List<BookEntity>> =
        books.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val studyCards = cards.observeByLanguage(STUDY_LANGUAGE)

    /** Words already in the user's cards, highlighted in the text. */
    val savedWords: StateFlow<Set<String>> = studyCards
        .map { list -> list.mapTo(HashSet()) { it.word } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Words marked «Знаю» by hand or by HSK level. */
    val markedKnown: StateFlow<Set<String>> = knownDao.observeWords()
        .map { it.toHashSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Everything the reader treats as known: marked words plus cards learned in reviews (30+ days). */
    val knownWords: StateFlow<Set<String>> = combine(knownDao.observeWords(), studyCards) { marked, list ->
        HashSet<String>(marked.size + list.size).apply {
            addAll(marked)
            list.filter { it.isLearned() }.forEach { add(it.word) }
        } as Set<String>
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _pinyinMode = MutableStateFlow(
        PinyinMode.entries.firstOrNull { it.name == prefs.getString("pinyin", null) } ?: PinyinMode.ALL
    )
    val pinyinMode: StateFlow<PinyinMode> = _pinyinMode.asStateFlow()

    fun setPinyinMode(mode: PinyinMode) {
        _pinyinMode.value = mode
        prefs.edit().putString("pinyin", mode.name).apply()
    }

    private val _levelMark = MutableStateFlow(
        LevelMark(
            HskSystem.entries.firstOrNull { it.name == prefs.getString("hskSystem", null) } ?: HskSystem.HSK3,
            prefs.getInt("hskLevel", 0)
        )
    )
    val levelMark: StateFlow<LevelMark> = _levelMark.asStateFlow()

    fun setKnown(word: String, known: Boolean) {
        viewModelScope.launch {
            if (known) knownDao.put(KnownWord(word, "manual")) else knownDao.remove(word)
        }
    }

    /** Marks every word up to [level] of [system] as known (0 removes the level marks). Manual marks stay. */
    fun setLevel(system: HskSystem, level: Int, onDone: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            onDone(runCatching {
                val engine = engineDeferred.await()
                val words = withContext(Dispatchers.Default) {
                    if (level == 0) emptyList() else {
                        val listed = engine.hsk.wordsUpTo(system, level).toHashSet()
                        listed + KnownExpansion.expand(listed, engine.dictionary)
                    }
                }
                val tag = (if (system == HskSystem.HSK3) "hsk3:" else "hsk2:") + level
                val added = knownDao.setLevelMarks(words.map { KnownWord(it, tag) })
                _levelMark.value = LevelMark(system, level)
                prefs.edit().putString("hskSystem", system.name).putInt("hskLevel", level)
                    .putInt("hskData", HSK_DATA_VERSION).apply()
                added
            })
        }
    }

    private val _open = MutableStateFlow<OpenBook?>(null)
    val open: StateFlow<OpenBook?> = _open.asStateFlow()

    private val _engine = MutableStateFlow<ZhEngine?>(null)
    val engine: StateFlow<ZhEngine?> = _engine.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { _engine.value = runCatching { engineDeferred.await() }.getOrNull() }
        // HSK lists were corrected after the level was chosen: mark the same level again with the new data.
        val mark = _levelMark.value
        if (mark.level > 0 && prefs.getInt("hskData", 1) < HSK_DATA_VERSION) setLevel(mark.system, mark.level) {}
    }

    companion object {
        /** 2: official HSK 2.0 lists, erhua forms, derived words (他们, 那些, 没有). */
        const val HSK_DATA_VERSION = 2
    }

    fun clearMessage() { _message.value = null }

    /** Copies the chosen file into private storage, checks that it opens, and adds it to the library. */
    fun importBook(uri: Uri, onDone: (Result<Long>) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            onDone(runCatching<Long> {
                withContext(Dispatchers.IO) {
                    val name = "book-${System.currentTimeMillis()}.epub"
                    val target = File(booksDir, name)
                    val input = app.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")
                    input.use { src -> target.outputStream().use { src.copyTo(it, 1 shl 16) } }
                    try {
                        EpubBook(target).use { epub ->
                            books.insert(
                                BookEntity(
                                    title = epub.title.ifBlank { displayName(uri) ?: "Без названия" },
                                    author = epub.author,
                                    fileName = name,
                                    chapterCount = epub.chapters.size
                                )
                            )
                        }
                    } catch (e: Exception) {
                        target.delete()
                        throw e
                    }
                }
            })
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null }
    }.getOrNull()

    fun deleteBook(book: BookEntity) {
        viewModelScope.launch {
            if (_open.value?.entity?.id == book.id) closeBook()
            withContext(Dispatchers.IO) { File(booksDir, book.fileName).delete() }
            books.delete(book.id)
        }
    }

    fun openBook(id: Long) {
        viewModelScope.launch {
            openMutex.withLock {
                closeBookInternal()
                val entity = books.get(id) ?: return@withLock
                val epub = runCatching {
                    withContext(Dispatchers.IO) { EpubBook(File(booksDir, entity.fileName)) }
                }.getOrElse {
                    _message.value = "Не удалось открыть книгу: ${it.message}"
                    return@withLock
                }
                _open.value = OpenBook(entity, epub)
                loadChapterLocked(entity.chapterIndex.coerceIn(0, epub.chapters.lastIndex))
                if (entity.analyzedTokens == 0) analyzeBook(entity.id)
            }
        }
    }

    fun goToChapter(index: Int) {
        viewModelScope.launch { openMutex.withLock { loadChapterLocked(index) } }
    }

    private suspend fun loadChapterLocked(index: Int) {
        val current = _open.value ?: return
        if (index !in current.epub.chapters.indices) return
        _open.value = current.copy(loading = true, error = null)
        val result = runCatching {
            val engine = engineDeferred.await()
            withContext(Dispatchers.Default) { ChapterBuilder.build(current.epub, index, engine) }
        }
        val now = _open.value ?: return
        _open.value = result.fold(
            onSuccess = { now.copy(chapter = it, loading = false) },
            onFailure = { now.copy(loading = false, error = "Глава не открылась: ${it.message}") }
        )
        if (result.isSuccess && index != now.entity.chapterIndex) {
            savePosition(index, 0)
        }
    }

    fun savePosition(chapter: Int, block: Int) {
        val current = _open.value ?: return
        val entity = current.entity.copy(chapterIndex = chapter, blockIndex = block)
        _open.value = current.copy(entity = entity)
        viewModelScope.launch { books.savePosition(entity.id, chapter, block) }
    }

    fun closeBook() {
        viewModelScope.launch { openMutex.withLock { closeBookInternal() } }
    }

    private fun closeBookInternal() {
        _open.value?.epub?.let { runCatching { it.close() } }
        _open.value = null
        images.evictAll()
    }

    /** Decodes a book image, downsampled to at most [maxWidth] pixels wide. */
    suspend fun image(path: String, maxWidth: Int): Bitmap? {
        val key = "$path@$maxWidth"
        images.get(key)?.let { return it }
        val epub = _open.value?.epub ?: return null
        return withContext(Dispatchers.IO) {
            val bytes = runCatching { epub.bytes(path) }.getOrNull() ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }?.also { images.put(key, it) }
    }

    // ---- Book vocabulary ----

    private val _analysis = MutableStateFlow<Map<Long, Float>>(emptyMap())
    /** Books being analysed, with progress 0..1. */
    val analysis: StateFlow<Map<Long, Float>> = _analysis.asStateFlow()

    fun bookWords(bookId: Long) = books.observeWords(bookId)

    /** Counts every word of the book once, in the background. Safe to call repeatedly. */
    fun analyzeBook(bookId: Long) {
        if (bookId in _analysis.value) return
        _analysis.value = _analysis.value + (bookId to 0f)
        viewModelScope.launch {
            runCatching {
                val entity = books.get(bookId) ?: return@runCatching
                val engine = engineDeferred.await()
                val result = withContext(Dispatchers.Default) {
                    EpubBook(File(booksDir, entity.fileName)).use { epub ->
                        BookAnalyzer.analyze(bookId, epub, engine.segmenter, engine.dictionary) { done, total ->
                            _analysis.value = _analysis.value + (bookId to done.toFloat() / total)
                        }
                    }
                }
                books.saveAnalysis(bookId, result.words, result.tokens)
                _open.value?.let { o -> if (o.entity.id == bookId) _open.value = o.copy(entity = o.entity.copy(analyzedTokens = result.tokens)) }
            }.onFailure { _message.value = "Не удалось разобрать словарь книги: ${it.message}" }
            _analysis.value = _analysis.value - bookId
        }
    }

    fun setIgnored(bookId: Long, word: String, ignored: Boolean) {
        viewModelScope.launch { books.setIgnored(bookId, word, ignored) }
    }

    /**
     * Adds chosen words to the cards, each with its first sentence in [chapter] or, if absent,
     * in the chapter where it first occurs in the book. onDone gets the number of new cards.
     */
    fun addCandidates(book: BookEntity, words: List<StudyCandidate>, chapter: ChapterContent?, onDone: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            onDone(runCatching {
                val engine = engineDeferred.await()
                val loaded = HashMap<Int, ChapterContent>()
                chapter?.let { loaded[it.index] = it }
                val epub = _open.value?.epub?.takeIf { _open.value?.entity?.id == book.id }
                var created = 0
                val own = if (epub == null) withContext(Dispatchers.IO) { EpubBook(File(booksDir, book.fileName)) } else null
                try {
                    for (w in words) {
                        val source = epub ?: own!!
                        val c = chapter?.takeIf { ch -> VocabularyStats.firstSentence(ch, w.word) != null }
                            ?: loaded.getOrPut(w.firstChapter) {
                                withContext(Dispatchers.Default) { ChapterBuilder.build(source, w.firstChapter, engine) }
                            }
                        val sentence = VocabularyStats.firstSentence(c, w.word) ?: w.word
                        val reading = engine.lookup(w.word)?.pinyin ?: w.pinyin
                        val translation = engine.lookup(w.word)?.shortGloss() ?: w.gloss
                        val isNew = cards.saveFromReader(
                            STUDY_LANGUAGE,
                            ReaderSave(w.word, reading, translation, sentence, book.id, book.title, c.index, c.title)
                        )
                        if (isNew) created++
                    }
                } finally {
                    own?.close()
                }
                created
            })
        }
    }

    fun saveWord(save: ReaderSave, onDone: (Result<Boolean>) -> Unit) {
        viewModelScope.launch { onDone(runCatching { cards.saveFromReader(STUDY_LANGUAGE, save) }) }
    }

    override fun onCleared() {
        closeBookInternal()
    }
}
