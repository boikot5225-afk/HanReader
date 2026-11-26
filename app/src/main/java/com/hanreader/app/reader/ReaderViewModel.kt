package com.hanreader.app.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.hanreader.app.BookEntity
import com.hanreader.app.KnownWord
import com.hanreader.app.isLearned
import com.hanreader.app.LexiconDatabase
import com.hanreader.app.ReaderSave
import com.hanreader.app.epub.BookFiles
import com.hanreader.app.epub.BookFormat
import com.hanreader.app.epub.BookSource
import com.hanreader.app.epub.EpubException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** The language this build reads (cards, speech): see [com.hanreader.app.lang.Languages]. */
val STUDY_LANGUAGE: String get() = com.hanreader.app.lang.Languages.current.tag

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

class ReaderViewModel @JvmOverloads constructor(app: Application,
    private val aiOverride: com.hanreader.app.ai.ContextAi? = null,
    private val loadTimeoutMs: Long = READER_LOAD_TIMEOUT_MS,
    private val bookOpener: suspend (File) -> BookSource = { BookFiles.open(it) }) : AndroidViewModel(app) {
    private val db = LexiconDatabase.get(app)
    private val books = db.bookDao()
    private val cards = db.cardDao()
    private val knownDao = db.knownWordDao()
    /** The tag of the language being read: marks, names and meanings are kept per language. */
    private val lang = com.hanreader.app.lang.Languages.current.tag
    private val prefs = app.getSharedPreferences("reader_settings", android.content.Context.MODE_PRIVATE)
    private val engineDeferred = com.hanreader.app.lang.LanguageEngines.warmUp(app)
    /** The dictionary alone: chapters, analysis and search need nothing more and start at once. */
    private val engineCore = com.hanreader.app.lang.LanguageEngines.core(app)
    private val booksDir = File(app.filesDir, "books").apply { mkdirs() }
    val contextAi by lazy { aiOverride ?: com.hanreader.app.ai.ContextAi.get(app) }

    suspend fun checkAi(fragment: com.hanreader.app.ai.AiFragment, automatic: Boolean, visible: (() -> Boolean)? = null) {
        suspend fun stillValid(): Boolean {
            val open = _open.value
            val chapter = open?.chapter
            return if (open == null || chapter == null || open.loading || open.entity.id != fragment.book || chapter.index != fragment.chapter) false
            else {
                val engine = engineDeferred.await()
                val meanings = db.userMeaningDao().all().filter { it.language.isEmpty() || it.language == lang }.associate { it.word to it.meaning }
                withContext(Dispatchers.Default) {
                    val fresh = com.hanreader.app.ai.AiContext.build(fragment.book, chapter, fragment.block,
                        open.epub.chapters[chapter.index].path, engine, meanings, com.hanreader.app.AppearanceStore.appearance.value.esVariant.name,
                        includeMissing = true)
                    fresh != null && fresh.contextHash == fragment.contextHash && fragment.terms.all { old ->
                        fresh.terms.any { it.start == old.start && it.base == old.base && it.lemma == old.lemma &&
                            (it.reading == old.reading || old.end > it.end) }
                    }
                }
            }
        }
        try {
            if (automatic && visible != null) contextAi.repository.checkVisible(fragment, visible, allowMissing = true, stillValid = ::stillValid)
            else contextAi.repository.check(fragment, automatic, ::stillValid)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { contextAi.repository.notice("Не удалось прочитать или сохранить данные проверки. Обычный словарь доступен.") }
    }

    private val openMutex = Mutex()
    private var openingJob: kotlinx.coroutines.Job? = null
    private val preparedChapters = PreparedChapterCache()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val analysisDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val _openingError = MutableStateFlow<String?>(null)
    val openingError: StateFlow<String?> = _openingError.asStateFlow()
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** The books of the language being read (see [com.hanreader.app.lang.BookLanguages]). */
    val library: StateFlow<List<BookEntity>> =
        books.observeAll().combine(com.hanreader.app.lang.BookLanguages.changes) { all, _ ->
            val tag = com.hanreader.app.lang.Languages.current.tag
            all.filter { com.hanreader.app.lang.BookLanguages.of(app, it.id) == tag }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    val markedKnown: StateFlow<Set<String>> = knownDao.observeWords(lang)
        .map { it.toHashSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Everything the reader treats as known: marked words plus cards learned in reviews (30+ days). */
    val knownWords: StateFlow<Set<String>> = combine(knownDao.observeWords(lang), knownDao.observeUnknown(lang), studyCards) { marked, unknown, list ->
        val no = unknown.toHashSet()
        val words = HashSet<String>(marked.size + list.size).apply {
            addAll(marked)
            list.filter { it.isLearned() }.forEach { add(it.word) }
            removeAll(no)
        }
        KnownWords(words, no) as Set<String>
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** How [word] is marked: "manual", "hsk3:4", "unknown"... or null. */
    fun knownMark(word: String) = knownDao.observe(lang, word)

    private val _pinyinMode = MutableStateFlow(
        PinyinMode.entries.firstOrNull { it.name == prefs.getString("pinyin", null) } ?: PinyinMode.ALL
    )
    val pinyinMode: StateFlow<PinyinMode> = _pinyinMode.asStateFlow()

    fun setPinyinMode(mode: PinyinMode) {
        _pinyinMode.value = mode
        prefs.edit().putString("pinyin", mode.name).apply()
    }

    private val chinese = com.hanreader.app.lang.Languages.current == com.hanreader.app.lang.Languages.CHINESE
    /** The chosen level is kept per language: HSK for Chinese, A1–C2 for Spanish. */
    private val levelKey = if (chinese) "hskLevel" else "level_" + com.hanreader.app.lang.Languages.current.tag
    private val _levelMark = MutableStateFlow(
        LevelMark(
            HskSystem.entries.firstOrNull { it.name == prefs.getString("hskSystem", null) } ?: HskSystem.HSK3,
            prefs.getInt(levelKey, 0)
        )
    )
    val levelMark: StateFlow<LevelMark> = _levelMark.asStateFlow()

    /** «Знаю» / «Не знаю»: both override the level, the cards and the word's parts. */
    fun setKnown(word: String, known: Boolean) {
        viewModelScope.launch { knownDao.put(KnownWord(word, if (known) "manual" else UNKNOWN, language = lang)) }
    }

    /** Removes the reader's own mark: the word is known again only if the chosen level has it. */
    fun clearKnown(word: String) {
        viewModelScope.launch {
            knownDao.remove(lang, word)
            val mark = _levelMark.value
            if (mark.level == 0) return@launch
            val engine = runCatching { engineDeferred.await() }.getOrNull() as? ZhEngine ?: return@launch
            val inLevel = withContext(Dispatchers.Default) { levelWords(engine, mark.system, mark.level).contains(word) }
            if (inLevel) knownDao.put(KnownWord(word, levelTag(mark.system, mark.level), language = lang))
        }
    }

    private fun levelTag(system: HskSystem, level: Int) = (if (system == HskSystem.HSK3) "hsk3:" else "hsk2:") + level

    private fun levelWords(engine: ZhEngine, system: HskSystem, level: Int): Set<String> {
        if (level == 0) return emptySet()
        // Both lists: HSK 2.0 leaves out words HSK 3.0 has at low levels (事, 那些) and the
        // other way round, so the same vocabulary size of the other system counts too.
        val listed = engine.hsk.wordsUpTo(system, level).toHashSet()
        val other = if (system == HskSystem.HSK2) HskSystem.HSK3 else HskSystem.HSK2
        listed += engine.hsk.wordsUpTo(other, HskLevels.equivalent(system, level))
        return listed + engine.expandKnown(listed)
    }

    /**
     * Marks every word up to [level] as known (0 removes the level marks). Manual marks stay.
     * Chinese: [system] is HSK 3.0 or 2.0. Another language: its own levels ([system] ignored),
     * marked "es-ES:3", so the Chinese marks stay as they are.
     */
    fun setLevel(system: HskSystem, level: Int, onDone: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            onDone(runCatching {
                val any = engineDeferred.await()
                if (any !is ZhEngine) {
                    val levels = any.levels ?: throw IllegalStateException("У этого языка нет уровней")
                    val prefix = com.hanreader.app.lang.Languages.current.tag + ":"
                    val words = if (level == 0) emptySet() else withContext(Dispatchers.Default) { levels.knownAt(level) }
                    val added = knownDao.setLevelMarks(words.map { KnownWord(it, prefix + level, language = lang) }, prefix)
                    _levelMark.value = LevelMark(system, level)
                    prefs.edit().putInt(levelKey, level).apply()
                    return@runCatching added
                }
                val engine = any
                val words = withContext(Dispatchers.Default) { levelWords(engine, system, level) }
                val tag = levelTag(system, level)
                val added = knownDao.setLevelMarks(words.map { KnownWord(it, tag, language = lang) })
                _levelMark.value = LevelMark(system, level)
                prefs.edit().putString("hskSystem", system.name).putInt("hskLevel", level)
                    .putInt("hskData", HSK_DATA_VERSION).apply()
                added
            })
        }
    }

    private val _open = MutableStateFlow<OpenBook?>(null)
    val open: StateFlow<OpenBook?> = _open.asStateFlow()

    private val _aiReady = MutableStateFlow(false)
    val aiReady: StateFlow<Boolean> = _aiReady.asStateFlow()
    private val _engine = MutableStateFlow<com.hanreader.app.lang.LanguageModule?>(null)
    val engine: StateFlow<com.hanreader.app.lang.LanguageModule?> = _engine.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // First the dictionary (milliseconds), then the full engine with Russian, HSK and names.
        viewModelScope.launch {
            runCatching { engineCore.await() }.getOrNull()?.let { if (_engine.value == null) _engine.value = it }
            val full = runCatching { engineDeferred.await() }.getOrNull()
            _engine.value = full ?: _engine.value
            // Do not pay to check the Chinese core's temporary English fallbacks before Russian loads.
            _aiReady.value = full != null
        }
        // HSK lists were corrected after the level was chosen: mark the same level again with the new data.
        val mark = _levelMark.value
        if (chinese && mark.level > 0 && prefs.getInt("hskData", 1) < HSK_DATA_VERSION) setLevel(mark.system, mark.level) {}
        // Simplified / traditional chosen in the settings: redraw the open chapter in that script.
        viewModelScope.launch {
            com.hanreader.app.AppearanceStore.appearance.map { it.script }.distinctUntilChanged().drop(1)
                .collect { _open.value?.let { rebuildOpenChapter(it) } }
        }
    }

    companion object {
        /** 2: official HSK 2.0 lists, erhua forms, derived words; 3: the other system's words of the same size. */
        const val HSK_DATA_VERSION = 3

        /** Source of a «Не знаю» mark in known_words. */
        const val UNKNOWN = "unknown"

        /** Saved block index meaning "the end of the chapter". */
        const val END_OF_CHAPTER = Int.MAX_VALUE / 2

        private const val COVER_WIDTH = 360

        /** A cautious start for a learner: about 200 characters a minute. */
        const val DEFAULT_CPM = 200f
    }

    fun clearMessage() { _message.value = null }

    /** One ordinary library copy, on demand. Never downloads, resets progress or duplicates it. */
    fun openDemoBook(onDone: (Result<Long>) -> Unit) {
        val sample = DemoBooks.forLanguage(lang)
        if (sample == null) { onDone(Result.failure(EpubException("Для этого языка пока нет пробной книги"))); return }
        viewModelScope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) {
                    DemoBooks.importMutex.withLock {
                        val existing = books.findByFileName(sample.fileName)
                        val target = File(booksDir, sample.fileName)
                        if (existing != null && target.isFile && target.length() > 0) return@withLock existing.id
                        val temp = File.createTempFile("demo-", ".tmp", booksDir)
                        try {
                            getApplication<Application>().assets.open(sample.asset).use { input ->
                                temp.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (existing != null) {
                                // Restore a missing bundled file without losing bookmarks or meanings.
                                if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
                                existing.id
                            } else importFile(temp, System.currentTimeMillis(), sample.title, sample.fileName)
                        } finally { temp.delete() }
                    }
                })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { Result.failure(EpubException("Не удалось открыть пробную книгу. Попробуй ещё раз.")) }
            onDone(result)
        }
    }

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
                    try {
                        input.use { src -> temp.outputStream().use { com.hanreader.app.epub.BookLimits.copy(src, it) } }
                    } catch (e: Exception) {
                        temp.delete()
                        throw e
                    }
                    importFile(temp, stamp, displayName(uri))
                }
            })
        }
    }

    /**
     * Pasted or shared text as a book of its own: tidied (line ends, trailing spaces, runs of blank
     * lines), refused without Chinese, then stored and opened like a TXT file. [title]: blank takes
     * the first line.
     */
    fun importText(title: String?, text: String, onDone: (Result<Long>) -> Unit) {
        viewModelScope.launch {
            onDone(runCatching<Long> {
                withContext(Dispatchers.IO) {
                    val clean = PastedText.tidy(text)
                    if (clean.none { com.hanreader.app.lang.Languages.current.isWordChar(it) }) throw EpubException(com.hanreader.app.lang.Languages.current.noWordsMessage)
                    val stamp = System.currentTimeMillis()
                    val temp = File(booksDir, "import-$stamp.tmp")
                    temp.writeText(clean, Charsets.UTF_8)
                    importFile(temp, stamp, title?.trim()?.takeIf { it.isNotEmpty() } ?: PastedText.title(clean))
                }
            })
        }
    }

    /** Recognises, checks and stores a book file copied to [temp]; returns the new book's id. */
    private suspend fun importFile(temp: File, stamp: Long, shownName: String?, storedName: String? = null): Long {
        val format = BookFiles.sniff(temp)
        if (format == null) {
            temp.delete()
            throw EpubException("Это не книга: поддерживаются EPUB, FB2, TXT, DOCX и DOC")
        }
        val name = storedName ?: "book-$stamp.${format.extension}"
        val target = File(booksDir, name)
        if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
        var language: com.hanreader.app.lang.LanguageInfo? = null
        val id = try {
            BookFiles.open(target, format).use { book ->
                // A book with no text at all (an empty file, an EPUB without chapters) would have
                // nothing to open: refuse it here instead of failing on every opening.
                val hasText = book.chapters.asSequence().take(50).any { c ->
                    book.blocks(c.index).any { it is com.hanreader.app.epub.EpubBlock.Text && it.text.isNotBlank() }
                }
                if (!hasText) throw EpubException("В книге не нашлось текста")
                val plain = format == BookFormat.TXT || format == BookFormat.DOC
                val title = if (plain || book.title.startsWith("book-") || book.title.startsWith("import-")) shownName ?: book.title else book.title
                val id = books.insert(
                    BookEntity(
                        title = title.ifBlank { shownName ?: "Без названия" },
                        author = book.author,
                        fileName = name,
                        chapterCount = book.chapters.size
                    )
                )
                runCatching { saveCover(id, book) }
                language = com.hanreader.app.lang.BookLanguages.detect(book).also { com.hanreader.app.lang.BookLanguages.set(getApplication(), id, it.tag) }
                id
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        // A book in the other language goes to that language's library: say where it is.
        val l = language
        if (l != null && l != com.hanreader.app.lang.Languages.current) {
            throw EpubException("Книга на языке «${l.title.lowercase()}»: она добавлена в его библиотеку. Переключить язык: библиотека → «Язык» → «Сменить».")
        }
        return id
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

    /**
     * «Язык книги: сменить»: the book moves to that language's library (the library shows only
     * one language's books). Its word counts are of the old language, so they are dropped —
     * the next opening counts the book again.
     */
    fun setBookLanguage(book: BookEntity, language: com.hanreader.app.lang.LanguageInfo) {
        if (com.hanreader.app.lang.BookLanguages.of(getApplication(), book.id) == language.tag) return
        viewModelScope.launch {
            if (_open.value?.entity?.id == book.id) closeBook()
            com.hanreader.app.lang.BookLanguages.set(getApplication(), book.id, language.tag)
            withContext(Dispatchers.IO) {
                books.deleteWords(book.id)
                books.setAnalyzed(book.id, 0)
            }
            prefs.edit().remove("analysis_v_${book.id}").apply()
            _message.value = "«${book.title}» теперь в библиотеке языка «${language.title.lowercase()}». Переключить язык: библиотека → «Язык» → «Сменить»."
        }
    }

    private var chapterJob: kotlinx.coroutines.Job? = null
    private var loadGeneration = 0L
    private var loadWatchdog: kotlinx.coroutines.Job? = null
    private var retryRequest: (() -> Unit)? = null
    private val _loadingState = MutableStateFlow<ReaderLoadState?>(null)
    val loadingState: StateFlow<ReaderLoadState?> = _loadingState.asStateFlow()

    fun retryLoading() { retryRequest?.invoke() }

    private fun loadingStage(stage: ReaderLoadStage, generation: Long, keepContent: Boolean = false) {
        loadWatchdog?.cancel()
        val state = ReaderLoadState(stage, keepContent = keepContent)
        _loadingState.value = state
        // An independent timer can stop the UI even if a provider/parser ignores
        // coroutine cancellation. Late results are rejected by the generation check.
        loadWatchdog = viewModelScope.launch {
            kotlinx.coroutines.delay(loadTimeoutMs)
            if (generation == loadGeneration && _loadingState.value?.id == state.id && _loadingState.value?.failure == null) {
                openingJob?.cancel(); chapterJob?.cancel()
                loadingFailure(++loadGeneration, ReaderLoadFailure.TIMEOUT)
            }
        }
    }

    private fun loadingFailure(generation: Long, failure: ReaderLoadFailure) {
        if (generation != loadGeneration) return
        loadWatchdog?.cancel()
        val state = _loadingState.value ?: ReaderLoadState(ReaderLoadStage.FILE)
        _loadingState.value = state.copy(failure = failure)
        _openingError.value = failure.label
        _open.value = _open.value?.copy(loading = false, error = failure.label)
    }

    fun openBook(id: Long) {
        closeBook()
        val generation = ++loadGeneration
        retryRequest = { openBook(id) }
        loadingStage(ReaderLoadStage.FILE, generation)
        _openingError.value = null
        openingJob = viewModelScope.launch {
            var acquired: BookSource? = null
            var published = false
            try {
                kotlinx.coroutines.withTimeout(loadTimeoutMs) {
                    // Opening an external file must not hold the publication lock: a
                    // provider ignoring cancellation must not block the next book.
                    val entity = books.get(id) ?: throw EpubException("missing")
                    val epub = withContext(Dispatchers.IO) {
                        bookOpener(File(booksDir, entity.fileName)).also { acquired = it }
                    }
                    if (epub.chapters.isEmpty()) throw EpubException("empty")
                    if (generation != loadGeneration) return@withTimeout
                    openMutex.withLock {
                        if (generation != loadGeneration) return@withLock
                        closeBookInternal()
                        _open.value = OpenBook(entity, epub)
                        published = true
                        loadChapterLocked(entity.chapterIndex.coerceIn(0, epub.chapters.lastIndex), generation = generation)
                        if (generation == loadGeneration && _open.value?.error == null) {
                            if (prefs.getInt("analysis_v_${entity.id}", 0) < BookAnalyzer.VERSION) analyzeBook(entity.id)
                            else snapshotProgress(entity.id)
                        }
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                loadingFailure(generation, ReaderLoadFailure.TIMEOUT)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                loadingFailure(generation, ReaderLoadFailure.FILE)
            } finally {
                if (!published) runCatching { acquired?.close() }
            }
        }
    }

    /** Chapter changes are cancellable too, including rebuilds after vocabulary analysis. */
    fun goToChapter(index: Int, atEnd: Boolean = false, block: Int? = null, char: Int = 0) {
        chapterJob?.cancel()
        val generation = ++loadGeneration
        retryRequest = { goToChapter(index, atEnd, block, char) }
        chapterJob = viewModelScope.launch {
            openMutex.withLock { loadChapterLocked(index, atEnd, block, char, generation) }
        }
    }

    private fun rebuildOpenChapter(o: OpenBook) {
        val c = o.chapter ?: return
        goToChapter(c.index, block = o.entity.blockIndex, char = o.entity.charOffset)
    }

    private suspend fun loadChapterLocked(index: Int, atEnd: Boolean = false, block: Int? = null, char: Int = 0,
                                         generation: Long = loadGeneration) {
        val current = _open.value ?: return
        if (generation != loadGeneration || index !in current.epub.chapters.indices) return
        _open.value = current.copy(loading = true, error = null)
        val keepContent = current.chapter?.index == index
        loadingStage(ReaderLoadStage.DICTIONARY, generation, keepContent)
        var cacheKey: PreparedChapterKey? = null
        val result = runCatching {
            kotlinx.coroutines.withTimeout(loadTimeoutMs) {
                val engine = engineCore.await()
                if (generation != loadGeneration) throw kotlinx.coroutines.CancellationException()
                loadingStage(ReaderLoadStage.CHAPTER, generation, keepContent)
                val extra = namesToKeepWhole(current.entity.id)
                val blocked = splitDao.words().toHashSet()
                val key = PreparedChapterKey(current.epub, index, engine, extra.toSet(), blocked,
                    com.hanreader.app.AppearanceStore.appearance.value.script)
                cacheKey = key
                preparedChapters[key] ?: withContext(Dispatchers.Default) { ChapterBuilder.build(current.epub, index, engine, extra,
                    key.script, blocked, checkCancelled = { coroutineContext.ensureActive() }) }
            }
        }
        val failure = result.exceptionOrNull()
        if (failure is kotlinx.coroutines.CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
        if (generation != loadGeneration) return
        val now = _open.value?.takeIf { it.epub === current.epub } ?: return
        if (failure != null) {
            loadingFailure(generation, if (failure is kotlinx.coroutines.TimeoutCancellationException) ReaderLoadFailure.TIMEOUT else ReaderLoadFailure.CHAPTER)
            return
        }
        val content = result.getOrThrow()
        cacheKey?.let { preparedChapters.put(it, content) }
        _open.value = now.copy(chapter = content, loading = false)
        loadWatchdog?.cancel()
        _loadingState.value = null
        if (index != now.entity.chapterIndex || atEnd || block != null) {
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
        openingJob?.cancel(); chapterJob?.cancel(); loadWatchdog?.cancel()
        ++loadGeneration
        retryRequest = null
        val closing = _open.value
        closing?.entity?.id?.let { snapshotProgress(it) }
        _open.value = null
        preparedChapters.clear()
        images.evictAll()
        aspects.clear()
        _loadingState.value = null
        _openingError.value = null
        if (closing != null) viewModelScope.launch { openMutex.withLock { runCatching { closing.epub.close() } } }
    }

    /** Today's share of known words in the book: the statistics show how it grows. */
    fun snapshotProgress(bookId: Long) {
        viewModelScope.launch {
            runCatching {
                val book = books.get(bookId)?.takeIf { it.analyzedTokens > 0 } ?: return@launch
                val engine = engineDeferred.await()
                val words = books.observeWords(bookId).first()
                if (words.isEmpty()) return@launch
                val known = knownWords.first { it is KnownWords || it.isNotEmpty() }
                val percent = withContext(Dispatchers.Default) { VocabularyStats.knownPercent(words, book.analyzedTokens, known, engine) }
                statsDao.putProgress(com.hanreader.app.BookProgress(bookId, ReadingStats.today(), percent))
            }
        }
    }

    private fun closeBookInternal() {
        _open.value?.epub?.let { runCatching { it.close() } }
        _open.value = null
        images.evictAll()
        aspects.clear()
        preparedChapters.clear()
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
        }?.also { if (_open.value?.epub === epub) aspects[path] = it }
    }

    // ---- Names ----

    private val userNameDao = db.userNameDao()

    /** Words the reader marked «Это имя». */
    val userNames: StateFlow<Set<String>> = userNameDao.observeWords(lang).map { it.toHashSet() as Set<String> }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Names the segmenter keeps as one word: the reader's own and the ones found in this book. */
    private suspend fun namesToKeepWhole(bookId: Long): Set<String> {
        val own = userNameDao.allIn(lang).map { it.word }
        val found = books.ignoredWords(bookId)
        return (own + found).filterTo(HashSet()) { it.length in 2..8 && it.all { c -> com.hanreader.app.lang.Languages.current.isWordChar(c) } }
    }

    /** Marks or unmarks [word] as a name; the chapter is rebuilt so the name stays whole. */
    fun setUserName(word: String, isName: Boolean) {
        viewModelScope.launch {
            if (isName) userNameDao.put(com.hanreader.app.UserName(word, language = lang)) else userNameDao.remove(lang, word)
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

    // ---- Highlights and notes ----

    private val highlightDao = db.highlightDao()

    fun highlights(bookId: Long) = highlightDao.observe(bookId)

    val wikipedia by lazy { com.hanreader.app.wiki.WikipediaRepository(getApplication()) }

    private fun checkedNoteTarget(target: WikiNoteTarget): ReaderBlock.Text {
        val current = _open.value ?: error("closed")
        val chapter = current.chapter ?: error("closed")
        val block = chapter.blocks.getOrNull(target.block) as? ReaderBlock.Text ?: error("stale")
        require(current.entity.id == target.book && chapter.index == target.chapter && target.start >= 0 &&
            target.end <= block.shown.length && target.start < target.end && block.shown.substring(target.start, target.end) == target.term)
        return block
    }

    private suspend fun knowledgeRows(book: Long): List<com.hanreader.app.Highlight> {
        check(_open.value?.entity?.id == book && books.get(book) != null) { "Книга закрыта или удалена" }
        return highlightDao.all().filter { it.bookId == book }
    }

    private fun quoteText(target: WikiNoteTarget) = (if (target.term.length > target.sentence.length) target.term else target.sentence)
        .also { require(it.isNotBlank() && it.length <= 5_000) { "Выбери фрагмент не длиннее 5000 знаков" } }

    private suspend fun storeQuote(target: WikiNoteTarget, owner: com.hanreader.app.Highlight, rows: List<com.hanreader.app.Highlight>) {
        EntityLinks.parse(EntityLinks.encode(listOf(owner.text)))
        val samePlace = rows.filter { it.chapterIndex == target.chapter && it.blockIndex == target.block && it.startChar == target.start && it.endChar == target.end }
        val wide = rows.filter { it.chapterIndex == Highlights.BOOK_WIDE }
        val existing = samePlace.firstOrNull { BookKnowledge.owner(it, wide)?.id == owner.id }
            ?: samePlace.firstOrNull { it.entityName.isEmpty() && BookKnowledge.owner(it, wide) == null }
        if (existing != null) highlightDao.update(existing.copy(entityName = owner.text, contextQuote = quoteText(target)))
        else highlightDao.insert(com.hanreader.app.Highlight(bookId = target.book, chapterIndex = target.chapter, blockIndex = target.block,
            startChar = target.start, endChar = target.end, color = 2, text = target.term, chapterTitle = target.chapterTitle,
            entityName = owner.text, contextQuote = quoteText(target)))
    }

    suspend fun saveEntityQuote(target: WikiNoteTarget, destinationId: Long) {
        checkedNoteTarget(target)
        db.withTransaction {
            val rows = knowledgeRows(target.book)
            val wide = rows.filter { it.chapterIndex == Highlights.BOOK_WIDE }
            val owner = wide.firstOrNull { it.id == destinationId }?.let { EntityLinks.root(it, wide) } ?: error("Запись уже удалена")
            storeQuote(target, owner, rows)
        }
    }

    suspend fun attachExistingQuote(id: Long, destinationId: Long) {
        val book = _open.value?.entity?.id ?: error("closed")
        db.withTransaction {
            val rows = knowledgeRows(book)
            val source = rows.firstOrNull { it.id == id && it.chapterIndex >= 0 } ?: error("Фрагмент уже удалён")
            val wide = rows.filter { it.chapterIndex == Highlights.BOOK_WIDE }
            val owner = wide.firstOrNull { it.id == destinationId }?.let { EntityLinks.root(it, wide) } ?: error("Запись уже удалена")
            EntityLinks.parse(EntityLinks.encode(listOf(owner.text)))
            highlightDao.update(source.copy(entityName = owner.text, contextQuote = BookKnowledge.quote(source).take(5_000)))
        }
    }

    suspend fun promoteKnowledgeNote(id: Long): Long {
        val book = _open.value?.entity?.id ?: error("closed")
        return db.withTransaction { promoteKnowledgeNoteLocked(book, id) }
    }

    private suspend fun promoteKnowledgeNoteLocked(book: Long, id: Long): Long {
        val rows = knowledgeRows(book)
        val source = rows.firstOrNull { it.id == id && it.chapterIndex >= 0 } ?: error("Фрагмент уже удалён")
        EntityLinks.parse(EntityLinks.encode(listOf(source.text)))
        val wide = rows.filter { it.chapterIndex == Highlights.BOOK_WIDE }
        val owner = BookKnowledge.owner(source.copy(entityName = source.text), wide)
        val ownerId = owner?.id ?: highlightDao.insert(com.hanreader.app.Highlight(bookId = book,
            chapterIndex = Highlights.BOOK_WIDE, blockIndex = 0, startChar = 0, endChar = 0, color = source.color,
            text = source.text, note = source.note, chapterTitle = Highlights.BOOK_WIDE_TITLE))
        highlightDao.update(source.copy(entityName = owner?.text ?: source.text, contextQuote = BookKnowledge.quote(source).take(5_000)))
        return ownerId
    }

    suspend fun detachEntityQuote(id: Long) {
        val book = _open.value?.entity?.id ?: error("closed")
        db.withTransaction {
            val source = knowledgeRows(book).firstOrNull { it.id == id && it.chapterIndex >= 0 } ?: error("Фрагмент уже удалён")
            highlightDao.update(source.copy(entityName = "", contextQuote = BookKnowledge.quote(source).take(5_000)))
        }
    }

    suspend fun saveKnowledgeWikipedia(id: Long, article: com.hanreader.app.wiki.WikiArticle, definition: String) {
        val book = _open.value?.entity?.id ?: error("closed")
        db.withTransaction {
            val rows = knowledgeRows(book)
            val source = rows.firstOrNull { it.id == id } ?: error("Запись уже удалена")
            val existing = if (source.chapterIndex == Highlights.BOOK_WIDE)
                EntityLinks.root(source, rows.filter { it.chapterIndex == Highlights.BOOK_WIDE }) ?: error("Неоднозначная связь") else source
            val target = WikiNoteTarget(book, existing.chapterIndex, existing.chapterTitle, existing.blockIndex, existing.startChar,
                existing.endChar, existing.text, if (existing.chapterIndex >= 0) BookKnowledge.quote(existing) else "")
            val note = wikipediaNote(target, article, definition)
            if (!existing.note.contains(note)) highlightDao.update(existing.copy(note = existing.note.takeIf { it.isNotBlank() }?.let { "$it\n\n$note" } ?: note))
        }
    }

    suspend fun saveKnowledgeNote(id: Long, note: String) {
        require(note.length <= 50_000)
        val book = _open.value?.entity?.id ?: error("closed")
        db.withTransaction {
            val source = knowledgeRows(book).firstOrNull { it.id == id } ?: error("Запись уже удалена")
            highlightDao.updateNoteAndColor(id, book, note, source.color)
        }
    }

    suspend fun saveWikipediaNote(target: WikiNoteTarget, article: com.hanreader.app.wiki.WikiArticle, definition: String) {
        val block = checkedNoteTarget(target)
        val note = wikipediaNote(target, article, definition)
        db.withTransaction {
            val rows = knowledgeRows(target.book)
            val existing = Highlights.bookWideAt(rows.filter { it.chapterIndex == Highlights.BOOK_WIDE }, block, target.start, target.end)
                ?: rows.firstOrNull { it.chapterIndex == target.chapter && it.blockIndex == target.block && it.startChar == target.start && it.endChar == target.end }
            if (existing != null) {
                if (!existing.note.contains(note)) highlightDao.update(existing.copy(note = existing.note.takeIf { it.isNotBlank() }?.let { "$it\n\n$note" } ?: note))
                if (existing.chapterIndex == Highlights.BOOK_WIDE &&
                    runCatching { EntityLinks.encode(listOf(existing.text)) }.isSuccess) storeQuote(target, existing, rows)
            } else highlightDao.insert(com.hanreader.app.Highlight(bookId = target.book, chapterIndex = target.chapter,
                blockIndex = target.block, startChar = target.start, endChar = target.end, color = 2,
                note = note, text = target.term, chapterTitle = target.chapterTitle))
        }
    }

    /** Highlights characters [start, end) of paragraph [block]. */
    fun addHighlight(chapter: ChapterContent, block: Int, start: Int, end: Int, color: Int, note: String = "") {
        val o = _open.value ?: return
        val b = chapter.blocks.getOrNull(block) as? ReaderBlock.Text ?: return
        if (start < 0 || end > b.shown.length || start >= end) return
        viewModelScope.launch {
            highlightDao.insert(
                com.hanreader.app.Highlight(
                    bookId = o.entity.id, chapterIndex = chapter.index, blockIndex = block,
                    startChar = start, endChar = end, color = color, note = note,
                    text = b.shown.substring(start, end), chapterTitle = chapter.title
                )
            )
        }
    }

    /** Highlights [text] everywhere in the open book (a name and who it is): see [Highlights.BOOK_WIDE]. */
    fun addBookHighlight(text: String, color: Int, note: String = "") {
        val o = _open.value ?: return
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            highlightDao.insert(
                com.hanreader.app.Highlight(
                    bookId = o.entity.id, chapterIndex = Highlights.BOOK_WIDE, blockIndex = 0,
                    startChar = 0, endChar = 0, color = color, note = note,
                    text = t, chapterTitle = Highlights.BOOK_WIDE_TITLE
                )
            )
        }
    }

    fun updateHighlight(h: com.hanreader.app.Highlight) {
        viewModelScope.launch { highlightDao.updateNoteAndColor(h.id, h.bookId, h.note, h.color) }
    }

    /** A shared spelling points to an existing note; no source note is deleted or overwritten. */
    fun linkEntity(text: String, destination: Long?, seedNote: String = "", onDone: (Result<Unit>) -> Unit = {}) {
        val book = _open.value?.entity?.id ?: run { onDone(Result.failure(IllegalStateException("Книга закрыта"))); return }
        viewModelScope.launch {
            val result = runCatching { db.withTransaction {
                check(books.get(book) != null)
                val spelling = text.trim().replace(Regex("\\s+"), " ")
                EntityLinks.parse(EntityLinks.encode(listOf(spelling)))
                val rows = highlightDao.all().filter { it.bookId == book && it.chapterIndex == Highlights.BOOK_WIDE }
                if (destination == null) {
                    require(rows.none { EntityLinks.aliases(it).any { alias -> EntityLinks.key(alias) == EntityLinks.key(spelling) } }) { "Сначала отмени связь этого варианта с общей записью" }
                    if (rows.none { EntityLinks.key(it.text) == EntityLinks.key(spelling) }) highlightDao.insert(
                        com.hanreader.app.Highlight(bookId = book, chapterIndex = Highlights.BOOK_WIDE, blockIndex = 0,
                            startChar = 0, endChar = 0, color = 2, note = seedNote, text = spelling, chapterTitle = Highlights.BOOK_WIDE_TITLE))
                } else {
                    linkSpelling(rows, spelling, destination)
                }
            }; Unit }
            onDone(result)
        }
    }

    private suspend fun linkSpelling(rows: List<com.hanreader.app.Highlight>, text: String, destination: Long) {
        val spelling = text.trim().replace(Regex("\\s+"), " ")
        EntityLinks.parse(EntityLinks.encode(listOf(spelling)))
        val selected = rows.firstOrNull { it.id == destination } ?: error("Запись уже удалена")
        val owner = EntityLinks.root(selected, rows) ?: error("Неоднозначная связь")
        if (EntityLinks.key(owner.text) == EntityLinks.key(spelling)) return
        val collision = rows.firstOrNull { it != owner && EntityLinks.aliases(it).any { alias -> EntityLinks.key(alias) == EntityLinks.key(spelling) } }
        if (collision != null) {
            require(EntityLinks.root(collision, rows) == owner) { "Этот вариант уже связан с другой записью. Сначала отмени прежнюю связь." }
            return
        }
        val changed = owner.copy(aliasesJson = EntityLinks.encode(EntityLinks.aliases(owner) + spelling),
            blockedAliasesJson = EntityLinks.encode(EntityLinks.blocked(owner).filter { EntityLinks.key(it) != EntityLinks.key(spelling) }))
        val updated = rows.map { if (it.id == owner.id) changed else it }
        require(EntityLinks.root(changed, updated) != null) { "Эта связь создаст замкнутую цепочку" }
        EntityLinks.validate(updated)
        highlightDao.update(changed)
    }

    /** Choosing a saved local name promotes it and links the current spelling atomically. */
    fun linkSavedEntity(sourceId: Long, spelling: String, onDone: (Result<Unit>) -> Unit = {}) {
        val book = _open.value?.entity?.id ?: run { onDone(Result.failure(IllegalStateException("Книга закрыта"))); return }
        viewModelScope.launch {
            onDone(runCatching { db.withTransaction {
                val ownerId = promoteKnowledgeNoteLocked(book, sourceId)
                val wide = knowledgeRows(book).filter { it.chapterIndex == Highlights.BOOK_WIDE }
                linkSpelling(wide, spelling, ownerId)
            }; Unit })
        }
    }

    fun unlinkEntity(ownerId: Long, spelling: String, onDone: (Result<Unit>) -> Unit = {}) {
        val book = _open.value?.entity?.id ?: run { onDone(Result.failure(IllegalStateException("Книга закрыта"))); return }
        viewModelScope.launch {
            onDone(runCatching { db.withTransaction {
                val rows = highlightDao.all().filter { it.bookId == book && it.chapterIndex == Highlights.BOOK_WIDE }
                val owner = rows.firstOrNull { it.id == ownerId } ?: error("Запись уже удалена")
                require(EntityLinks.key(spelling) != EntityLinks.key(owner.text))
                for (member in rows) {
                    if (member.id != owner.id && EntityLinks.root(member, rows) == owner && EntityLinks.aliases(member).any { EntityLinks.key(it) == EntityLinks.key(spelling) }) {
                        highlightDao.update(member.copy(aliasesJson = EntityLinks.encode(EntityLinks.aliases(member).filter { EntityLinks.key(it) != EntityLinks.key(spelling) })))
                    }
                }
                val changed = owner.copy(
                    aliasesJson = EntityLinks.encode(EntityLinks.aliases(owner).filter { EntityLinks.key(it) != EntityLinks.key(spelling) }),
                    blockedAliasesJson = EntityLinks.encode(EntityLinks.blocked(owner) + spelling))
                highlightDao.update(changed)
            } })
        }
    }

    fun removeHighlight(id: Long) {
        viewModelScope.launch { highlightDao.delete(id) }
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
    private val statsDao = db.statsDao()
    private val meaningDao = db.userMeaningDao()
    private val splitDao = db.userSplitDao()

    /** Dictionary words the reader split (把头 -> 把 + 头), in every book. */
    val userSplits: StateFlow<Set<String>> = splitDao.observeWords()
        .map { it.toHashSet() as Set<String> }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Splits [word] into its characters (or shorter words) everywhere and redraws the chapter; [split] false joins it again. */
    fun setSplit(word: String, split: Boolean) {
        viewModelScope.launch {
            if (split) splitDao.put(com.hanreader.app.UserSplit(word)) else splitDao.remove(word)
            _open.value?.let { rebuildOpenChapter(it) }
        }
    }

    /** The reader's own meanings of words (word -> meaning). */
    val userMeanings: StateFlow<Map<String, String>> = meaningDao.observeAll(lang)
        .map { list -> list.associate { it.word to it.meaning } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Sets the reader's meaning of [word]; a blank one removes it. */
    fun setUserMeaning(word: String, meaning: String) {
        val m = meaning.trim()
        viewModelScope.launch {
            val l = com.hanreader.app.UserMeaning.languageOf(word, lang)
            if (m.isEmpty()) meaningDao.remove(l, word) else meaningDao.put(com.hanreader.app.UserMeaning(word, m, language = l))
        }
    }

    /** Days of reading and reviews, for the statistics screen. */
    /** Reading and reviews by day of the language being read (see [com.hanreader.app.lang.LanguageDays]). */
    val activity = statsDao.observeDays().map { com.hanreader.app.lang.LanguageDays.own(it) }
    val bookProgress = statsDao.observeProgress()
    /** All cards (new words per day, learned). */
    val allCards: StateFlow<List<com.hanreader.app.WordCard>> = studyCards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A page was on screen [millis] ms and had [chars] characters: today's reading and the speed estimate. */
    fun recordPage(chars: Int, millis: Long) {
        if (millis >= 2_000 && chars > 0) {
            viewModelScope.launch {
                statsDao.addReading(com.hanreader.app.lang.LanguageDays.key(ReadingStats.today()), millis.coerceAtMost(ReadingStats.MAX_PAGE_MILLIS), chars)
            }
        }
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
        }?.also { if (_open.value?.epub === epub) images.put(key, it) }
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
                val result = withContext(analysisDispatcher) {
                    BookFiles.open(File(booksDir, entity.fileName)).use { epub ->
                        engine.analyze(bookId, epub) { done, total ->
                            _analysis.value = _analysis.value + (bookId to done.toFloat() / total)
                        }
                    }
                }
                books.saveAnalysis(bookId, result.words, result.tokens)
                prefs.edit().putInt("analysis_v_$bookId", BookAnalyzer.VERSION).apply()
                // Names found now are kept whole: rebuild the open chapter in place.
                _open.value?.let { o -> if (o.entity.id == bookId && engine is ZhEngine) rebuildOpenChapter(o) }
                _open.value?.let { o -> if (o.entity.id == bookId) _open.value = o.copy(entity = o.entity.copy(analyzedTokens = result.tokens)) }
                snapshotProgress(bookId)
            }.onFailure {
                com.hanreader.app.CrashLog.record("Анализ словаря книги", it)
                _message.value = "Не удалось разобрать словарь книги: ${it.message}"
            }
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
                        val c = chapter?.takeIf { ch -> VocabularyStats.firstSentence(ch, w.word, engine) != null }
                            ?: loaded.getOrPut(w.firstChapter) {
                                withContext(Dispatchers.Default) { ChapterBuilder.build(source, w.firstChapter, engine, namesToKeepWhole(book.id)) }
                            }
                        val sentence = VocabularyStats.firstSentence(c, w.word, engine) ?: w.word
                        val reading = engine.reading(w.word) ?: w.pinyin
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
