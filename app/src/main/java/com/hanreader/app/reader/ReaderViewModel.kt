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
                    if (level == 0) emptyList() else engine.hsk.wordsUpTo(system, level)
                }
                val tag = (if (system == HskSystem.HSK3) "hsk3:" else "hsk2:") + level
                val added = knownDao.setLevelMarks(words.map { KnownWord(it, tag) })
                _levelMark.value = LevelMark(system, level)
                prefs.edit().putString("hskSystem", system.name).putInt("hskLevel", level).apply()
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
    }

    fun clearMessage() { _message.value = null }

    /** Copies the chosen file into private storage, checks that it opens, and adds it to the library. */
    fun importBook(uri: Uri, onDone: (Result<Long>) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            onDone(runCatching {
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

    fun saveWord(save: ReaderSave, onDone: (Result<Boolean>) -> Unit) {
        viewModelScope.launch { onDone(runCatching { cards.saveFromReader(STUDY_LANGUAGE, save) }) }
    }

    override fun onCleared() {
        closeBookInternal()
    }
}
