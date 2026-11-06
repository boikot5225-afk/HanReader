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
import com.hanreader.app.epub.BookFiles
import com.hanreader.app.epub.BookFormat
import com.hanreader.app.epub.BookSource
import com.hanreader.app.epub.EpubException
import com.hanreader.app.zh.isHan
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
    RECOMMENDED("Только над рекомендуемыми"),
    OFF("Выключен")
}

/** The level the user marked as known in one tap; level 0 = none. */
data class LevelMark(val system: HskSystem, val level: Int)

/** An open book: the EPUB file, its row in the database and the chapter on screen. */
data class OpenBook(
    val entity: BookEntity,
    val epub: BookSource,
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
    /** The dictionary alone: chapters, analysis and search need nothing more and start at once. */
    private val engineCore = ZhEngine.core(app)
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

    /** Words still being learned: in the cards and not learned yet (interval under 30 days). Underlined in the text. */
    val studyingWords: StateFlow<Set<String>> = studyCards
        .map { list -> list.filter { !it.isLearned() }.mapTo(HashSet()) { it.word } as Set<String> }
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
                        // Both lists: HSK 2.0 leaves out words HSK 3.0 has at low levels (事, 那些) and the
                        // other way round, so the same vocabulary size of the other system counts too.
                        val listed = engine.hsk.wordsUpTo(system, level).toHashSet()
                        val other = if (system == HskSystem.HSK2) HskSystem.HSK3 else HskSystem.HSK2
                        listed += engine.hsk.wordsUpTo(other, HskLevels.equivalent(system, level))
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
        // First the dictionary (milliseconds), then the full engine with Russian, HSK and names.
        viewModelScope.launch {
            runCatching { engineCore.await() }.getOrNull()?.let { if (_engine.value == null) _engine.value = it }
            _engine.value = runCatching { engineDeferred.await() }.getOrNull() ?: _engine.value
        }
        // HSK lists were corrected after the level was chosen: mark the same level again with the new data.
        val mark = _levelMark.value
        if (mark.level > 0 && prefs.getInt("hskData", 1) < HSK_DATA_VERSION) setLevel(mark.system, mark.level) {}
    }

    companion object {
        /** 2: official HSK 2.0 lists, erhua forms, derived words; 3: the other system's words of the same size. */
        const val HSK_DATA_VERSION = 3

        /** Saved block index meaning "the end of the chapter". */
        const val END_OF_CHAPTER = Int.MAX_VALUE / 2

        private const val COVER_WIDTH = 360

        /** A cautious start for a learner: about 200 characters a minute. */
        const val DEFAULT_CPM = 200f
    }

    fun clearMessage() { _message.value = null }

    /**
     * Copies the chosen file into private storage, recognises its format (EPUB, FB2, FB2.ZIP, TXT)
     * from the content, checks that it opens, saves a cover thumbnail and adds it to the library.
     */
    fun importBook(uri: Uri, onDone: (Result<Long>) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            onDone(runCatching<Long> {
                withContext(Dispatchers.IO) {
                    val stamp = System.currentTimeMillis()
                    val temp = File(booksDir, "import-$stamp.tmp")
                    val input = app.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")
                    input.use { src -> temp.outputStream().use { src.copyTo(it, 1 shl 16) } }
                    val format = BookFiles.sniff(temp)
                    if (format == null) {
                        temp.delete()
                        throw EpubException("Это не книга: поддерживаются EPUB, FB2, TXT, DOCX и DOC")
                    }
                    val name = "book-$stamp.${format.extension}"
                    val target = File(booksDir, name)
                    if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
                    try {
                        BookFiles.open(target, format).use { book ->
                            val shown = displayName(uri)
                            val plain = format == BookFormat.TXT || format == BookFormat.DOC
                            val title = if (plain || book.title.startsWith("book-") || book.title.startsWith("import-")) shown ?: book.title else book.title
                            val id = books.insert(
                                BookEntity(
                                    title = title.ifBlank { shown ?: "Без названия" },
                                    author = book.author,
                                    fileName = name,
                                    chapterCount = book.chapters.size
                                )
                            )
                            runCatching { saveCover(id, book) }
                            id
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
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()?.let { n -> n.replace(Regex("\\.(fb2\\.zip|epub|fb2|txt|docx|doc|zip)$", RegexOption.IGNORE_CASE), "") }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')

    // ---- Covers ----

    private val coversDir = File(app.filesDir, "covers").apply { mkdirs() }
    private val coverCache = object : LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    private fun coverFile(id: Long) = File(coversDir, "$id.jpg")
    private fun noCoverFile(id: Long) = File(coversDir, "$id.none")

    /** Saves a small JPEG of the book's cover, or a marker that the book has none. */
    private fun saveCover(id: Long, book: BookSource) {
        val bytes = book.cover()
        val bmp = bytes?.let {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(it, 0, it.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= COVER_WIDTH) sample *= 2
            BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bmp == null) { noCoverFile(id).writeText(""); return }
        coverFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    /** The cover thumbnail, made on first request for books imported before covers existed. */
    suspend fun cover(book: BookEntity): Bitmap? {
        coverCache.get(book.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = coverFile(book.id)
            if (!f.exists() && !noCoverFile(book.id).exists()) {
                runCatching { BookFiles.open(File(booksDir, book.fileName)).use { saveCover(book.id, it) } }
            }
            if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        }?.also { coverCache.put(book.id, it) }
    }

    // ---- Library view ----

    enum class LibrarySort(val title: String) { RECENT("Недавние"), ADDED("Добавленные"), TITLE("Название"), AUTHOR("Автор"), PROGRESS("Прогресс") }

    private val _sort = MutableStateFlow(LibrarySort.entries.firstOrNull { it.name == prefs.getString("librarySort", null) } ?: LibrarySort.RECENT)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()
    private val _grid = MutableStateFlow(prefs.getBoolean("libraryGrid", true))
    val grid: StateFlow<Boolean> = _grid.asStateFlow()

    fun setSort(s: LibrarySort) { _sort.value = s; prefs.edit().putString("librarySort", s.name).apply() }
    fun setGrid(on: Boolean) { _grid.value = on; prefs.edit().putBoolean("libraryGrid", on).apply() }

    fun sorted(list: List<BookEntity>, sort: LibrarySort): List<BookEntity> = when (sort) {
        LibrarySort.RECENT -> list // the DAO order: last opened, then added
        LibrarySort.ADDED -> list.sortedByDescending { it.addedAt }
        LibrarySort.TITLE -> list.sortedBy { it.title.lowercase() }
        LibrarySort.AUTHOR -> list.sortedWith(compareBy({ it.author.ifBlank { "\uFFFF" }.lowercase() }, { it.title.lowercase() }))
        LibrarySort.PROGRESS -> list.sortedByDescending { if (it.chapterCount > 0) (it.chapterIndex + 1f) / it.chapterCount else 0f }
    }

    fun deleteBook(book: BookEntity) {
        viewModelScope.launch {
            if (_open.value?.entity?.id == book.id) closeBook()
            withContext(Dispatchers.IO) {
                File(booksDir, book.fileName).delete()
                coverFile(book.id).delete(); noCoverFile(book.id).delete()
            }
            coverCache.remove(book.id)
            books.delete(book.id)
        }
    }

    fun openBook(id: Long) {
        viewModelScope.launch {
            openMutex.withLock {
                closeBookInternal()
                val entity = books.get(id) ?: return@withLock
                val epub = runCatching {
                    withContext(Dispatchers.IO) { BookFiles.open(File(booksDir, entity.fileName)) }
                }.getOrElse {
                    _message.value = "Не удалось открыть книгу: ${it.message}"
                    return@withLock
                }
                _open.value = OpenBook(entity, epub)
                loadChapterLocked(entity.chapterIndex.coerceIn(0, epub.chapters.lastIndex))
                if (entity.analyzedTokens == 0 || prefs.getInt("analysis_v_${entity.id}", 1) < BookAnalyzer.VERSION) analyzeBook(entity.id)
            }
        }
    }

    /**
     * [atEnd]: open on the last page (turning back from the next chapter); [block]: open at that
     * paragraph, at character [char] of it.
     */
    fun goToChapter(index: Int, atEnd: Boolean = false, block: Int? = null, char: Int = 0) {
        viewModelScope.launch { openMutex.withLock { loadChapterLocked(index, atEnd, block, char) } }
    }

    /** Rebuilds the open chapter (names changed) without moving the reader. */
    private fun rebuildOpenChapter(o: OpenBook) {
        val c = o.chapter ?: return
        goToChapter(c.index, block = o.entity.blockIndex, char = o.entity.charOffset)
    }

    private suspend fun loadChapterLocked(index: Int, atEnd: Boolean = false, block: Int? = null, char: Int = 0) {
        val current = _open.value ?: return
        if (index !in current.epub.chapters.indices) return
        _open.value = current.copy(loading = true, error = null)
        val result = runCatching {
            val engine = engineCore.await()
            val extra = namesToKeepWhole(current.entity.id)
            withContext(Dispatchers.Default) { ChapterBuilder.build(current.epub, index, engine, extra) }
        }
        val now = _open.value ?: return
        _open.value = result.fold(
            onSuccess = { now.copy(chapter = it, loading = false) },
            onFailure = { now.copy(loading = false, error = "Глава не открылась: ${it.message}") }
        )
        if (result.isSuccess && (index != now.entity.chapterIndex || atEnd || block != null)) {
            savePosition(index, block ?: if (atEnd) END_OF_CHAPTER else 0, if (block != null) char else 0)
        }
    }

    /** [char]: the character inside paragraph [block] where the page starts (0: the paragraph's start). */
    fun savePosition(chapter: Int, block: Int, char: Int = 0) {
        val current = _open.value ?: return
        val entity = current.entity.copy(chapterIndex = chapter, blockIndex = block, charOffset = char)
        _open.value = current.copy(entity = entity)
        viewModelScope.launch { books.savePosition(entity.id, chapter, block, char) }
    }

    fun closeBook() {
        viewModelScope.launch { openMutex.withLock { closeBookInternal() } }
    }

    private fun closeBookInternal() {
        _open.value?.epub?.let { runCatching { it.close() } }
        _open.value = null
        images.evictAll()
    }

    private val aspects = HashMap<String, Float>()

    /** Height / width of a book image, from its header only; null if it cannot be read. */
    suspend fun imageAspect(path: String): Float? {
        aspects[path]?.let { return it }
        val epub = _open.value?.epub ?: return null
        return withContext(Dispatchers.IO) {
            val bytes = runCatching { epub.bytes(path) }.getOrNull() ?: return@withContext null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth > 0) o.outHeight.toFloat() / o.outWidth else null
        }?.also { aspects[path] = it }
    }

    // ---- Names ----

    private val userNameDao = db.userNameDao()

    /** Words the reader marked «Это имя». */
    val userNames: StateFlow<Set<String>> = userNameDao.observeWords().map { it.toHashSet() as Set<String> }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Names the segmenter keeps as one word: the reader's own and the ones found in this book. */
    private suspend fun namesToKeepWhole(bookId: Long): Set<String> {
        val own = userNameDao.all().map { it.word }
        val found = books.ignoredWords(bookId)
        return (own + found).filterTo(HashSet()) { it.length in 2..8 && it.all { c -> c.isHan() } }
    }

    /** Marks or unmarks [word] as a name; the chapter is rebuilt so the name stays whole. */
    fun setUserName(word: String, isName: Boolean) {
        viewModelScope.launch {
            if (isName) userNameDao.put(com.hanreader.app.UserName(word)) else userNameDao.remove(word)
            _open.value?.let { rebuildOpenChapter(it) }
        }
    }

    // ---- Bookmarks ----

    private val bookmarkDao = db.bookmarkDao()

    fun bookmarks(bookId: Long) = bookmarkDao.observe(bookId)

    fun addBookmark(chapter: ChapterContent, block: Int) {
        val o = _open.value ?: return
        val text = (chapter.blocks.getOrNull(block) as? ReaderBlock.Text)?.text
            ?: chapter.blocks.drop(block).filterIsInstance<ReaderBlock.Text>().firstOrNull()?.text.orEmpty()
        viewModelScope.launch {
            bookmarkDao.insert(
                com.hanreader.app.Bookmark(
                    bookId = o.entity.id, chapterIndex = chapter.index, blockIndex = block,
                    chapterTitle = chapter.title, snippet = text.replace('\n', ' ').take(60)
                )
            )
        }
    }

    fun removeBookmark(id: Long) {
        viewModelScope.launch { bookmarkDao.delete(id) }
    }

    // ---- Search in the book ----

    data class SearchState(
        val query: String = "",
        val hits: List<SearchHit> = emptyList(),
        val running: Boolean = false,
        val progress: Float = 0f
    )

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: kotlinx.coroutines.Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) { _search.value = SearchState(); return }
        val epub = _open.value?.epub ?: return
        _search.value = SearchState(q, emptyList(), running = true)
        searchJob = viewModelScope.launch {
            val engine = engineCore.await()
            val found = ArrayList<SearchHit>()
            withContext(Dispatchers.Default) {
                BookSearch.search(epub, q, engine, onProgress = { done, total ->
                    _search.value = SearchState(q, found.toList(), running = true, progress = done.toFloat() / total)
                }) { hit -> found += hit }
            }
            _search.value = SearchState(q, found.toList(), running = false, progress = 1f)
        }
    }

    // ---- Reading speed (Han characters per minute), for "≈ N min left" ----

    private val _speed = MutableStateFlow(prefs.getFloat("cpm", DEFAULT_CPM))
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** A page of [chars] characters was read in [millis]; implausible times are ignored. */
    fun recordPage(chars: Int, millis: Long) {
        if (chars < 20 || millis < 3_000 || millis > 5 * 60_000) return
        val sample = (chars / (millis / 60_000f)).coerceIn(30f, 1_500f)
        val next = _speed.value * 0.8f + sample * 0.2f
        _speed.value = next
        prefs.edit().putFloat("cpm", next).apply()
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
                val engine = engineCore.await()
                val result = withContext(Dispatchers.Default) {
                    BookFiles.open(File(booksDir, entity.fileName)).use { epub ->
                        BookAnalyzer.analyze(bookId, epub, engine.segmenter, engine.dictionary) { done, total ->
                            _analysis.value = _analysis.value + (bookId to done.toFloat() / total)
                        }
                    }
                }
                books.saveAnalysis(bookId, result.words, result.tokens)
                prefs.edit().putInt("analysis_v_$bookId", BookAnalyzer.VERSION).apply()
                // Names found now are kept whole: rebuild the open chapter in place.
                _open.value?.let { o -> if (o.entity.id == bookId) rebuildOpenChapter(o) }
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
                val own = if (epub == null) withContext(Dispatchers.IO) { BookFiles.open(File(booksDir, book.fileName)) } else null
                try {
                    for (w in words) {
                        val source = epub ?: own!!
                        val c = chapter?.takeIf { ch -> VocabularyStats.firstSentence(ch, w.word) != null }
                            ?: loaded.getOrPut(w.firstChapter) {
                                withContext(Dispatchers.Default) { ChapterBuilder.build(source, w.firstChapter, engine, namesToKeepWhole(book.id)) }
                            }
                        val sentence = VocabularyStats.firstSentence(c, w.word) ?: w.word
                        val reading = engine.lookup(w.word)?.pinyin ?: w.pinyin
                        val translation = engine.cardTranslation(w.word) ?: w.gloss
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
