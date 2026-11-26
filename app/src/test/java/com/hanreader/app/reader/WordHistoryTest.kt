package com.hanreader.app.reader

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.en.EnEngineTest
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.es.EsEngineTest
import com.hanreader.app.zh.ZhSegmenterTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.geometry.Rect

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordHistoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val engine by lazy { EsEngineTest.load() }
    private lateinit var db: LexiconDatabase
    private val source = EncounterSource("book:1", "Книга", 1)
    private fun chapter(text: String) = ChapterBuilder.build(TxtBook(text.toByteArray(), "Test"), 0, engine)
    private fun batch(text: String = "Hablo y hablamos.", source: EncounterSource = this.source): EncounterBatch {
        val ch = chapter(text)
        return WordHistory.batch(source, ch, listOf(WikiVisibleSpan(0, 0, text.length)), engine, 100)
    }
    @Before fun setup(): Unit = runBlocking {
        db = Room.inMemoryDatabaseBuilder(app, LexiconDatabase::class.java).build()
        db.bookDao().insert(BookEntity(1, "Книга", "", "test.epub", 1))
        Unit
    }
    @After fun close() { db.close() }

    @Test fun repeatedPagesAndOverlappingSpansDoNotInflateEncounters() = runBlocking {
        val dao = db.wordHistoryDao()
        val first = batch()
        dao.record(first); dao.record(first)
        val ch = chapter("Hablo y hablamos.")
        val overlapping = WordHistory.batch(source, ch, listOf(WikiVisibleSpan(0, 0, 16), WikiVisibleSpan(0, 0, 5)), engine, 999)
        dao.record(overlapping)
        assertEquals(WordHistoryStats(2, 0, 1), dao.stats(engine.info.tag, "hablar").first())
        assertTrue(dao.contexts(engine.info.tag, "hablar").first().all { it.firstSeen == 100L })
    }

    @Test fun familiarityJoinsWordFormsAcrossDaysAndRespondsToDictionaryLookups() = runBlocking {
        val dao = db.wordHistoryDao()
        val words = batch("Hablo y hablamos. Hablo y hablamos. Hablo y hablamos. Hablo y hablamos.")
        val occurrences = words.words.filter { it.lemma == "hablar" }
        assertEquals(8, occurrences.size)
        suspend fun stats() = dao.familiarity(engine.info.tag, listOf("hablar")).first().single()

        dao.record(words.copy(words = occurrences.take(2)))
        assertEquals(FamiliarityLevel.NEW, stats().level)
        dao.record(words.copy(words = occurrences.take(7)))
        assertEquals(FamiliarityLevel.REPEATED, stats().level)
        dao.record(words.copy(words = listOf(occurrences.last().copy(firstSeen = 2 * 86_400_000L))))
        assertEquals(WordFamiliarity("hablar", 8, 0, 0, 0), stats())
        assertEquals(FamiliarityLevel.REPEATED, stats().level)
        dao.record(words.copy(words = occurrences.take(3).map { it.copy(firstWithoutHint = 100L) }))
        assertEquals(FamiliarityLevel.REPEATED, stats().level)
        dao.record(words.copy(words = listOf(occurrences.last().copy(firstWithoutHint = 2 * 86_400_000L))))
        assertEquals(WordFamiliarity("hablar", 8, 0, 4, 2), stats())
        assertEquals(FamiliarityLevel.FAMILIAR, stats().level)
        dao.record(words.copy(words = listOf(occurrences.last())), lookup = true)
        assertEquals(WordFamiliarity("hablar", 8, 1, 3, 1), stats())
        assertEquals(FamiliarityLevel.REPEATED, stats().level)
        repeat(4) { dao.record(words.copy(words = listOf(occurrences.first())), lookup = true) }
        assertEquals(8L, stats().encounters)
        assertEquals(5L, stats().lookups)
        assertEquals(FamiliarityLevel.LOOKED_UP, stats().level)
        assertTrue(dao.familiarity("en-US", listOf("hablar")).first().isEmpty())
    }

    @Test fun fadedHintCanQualifyTheSamePlaceOnce() = runBlocking {
        val text = "Hablo y hablamos."
        val ch = chapter(text)
        val dao = db.wordHistoryDao()
        val session = WordHistorySession(source, ch, engine)
        val span = listOf(WikiVisibleSpan(0, 0, text.length))
        val block = ch.blocks.first() as ReaderBlock.Text
        val target = block.tokens.indexOfFirst { it.text == "Hablo" }
        assertTrue(target >= 0)
        val token = block.tokens[target]
        val hints = mapOf("Hablo" to "говорю")
        assertFalse(HistoryHints(hints).isUnassisted(0, target, token))
        assertFalse(HistoryHints(hints + ("@0:${token.start}" to "говорю"), emptyMap()).isUnassisted(0, target, token))
        assertFalse(HistoryHints(hints + ("@0:${token.start}" to ""), emptyMap()).isUnassisted(0, target, token))
        session.record(span, dao)
        val faded = HistoryHints(hints, emptyMap())
        assertEquals(1, session.record(span, dao) { bi, ti -> ti == target && faded.isUnassisted(bi, ti, token) })
        assertEquals(0, session.record(span, dao) { _, ti -> ti == target })
        val stats = dao.familiarity(engine.info.tag, listOf("hablar")).first().single()
        assertEquals(2L, stats.encounters)
        assertEquals(1L, stats.unassisted)
    }
    @Test fun lookupsAreIndependentAndAtomicAcrossConcurrentRequests() = runBlocking {
        val ch = chapter("Hablo y hablamos.")
        val lookup = WordHistory.batch(source, ch, listOf(WikiVisibleSpan(0, 0, 5)), engine, selection = true)
        coroutineScope { repeat(10) { launch(Dispatchers.Default) { db.wordHistoryDao().record(lookup, true) } } }
        assertEquals(WordHistoryStats(1, 10, 1), db.wordHistoryDao().stats(engine.info.tag, "hablar").first())
    }
    @Test fun differentSourcesAndChangedTextStaySeparate() = runBlocking {
        val dao = db.wordHistoryDao()
        dao.record(batch())
        dao.record(batch(source = EncounterSource("wiki:es:42", "Статья")))
        dao.record(batch("Hablo y hablamos mucho."))
        assertEquals(WordHistoryStats(6, 0, 2), dao.stats(engine.info.tag, "hablar").first())
        assertEquals(setOf("Книга", "Статья"), dao.contexts(engine.info.tag, "hablar").first().map { it.title }.toSet())
    }
    @Test fun deletionCascadesAndLateBookWriteDoesNotResurrectHistory() = runBlocking {
        val dao = db.wordHistoryDao()
        dao.record(batch()); dao.record(batch(source = EncounterSource("wiki:es:42", "Статья")))
        db.bookDao().delete(1)
        assertEquals(2, dao.stats(engine.info.tag, "hablar").first().encounters)
        assertTrue(runCatching { dao.record(batch()) }.isFailure)
        assertEquals(2, dao.stats(engine.info.tag, "hablar").first().encounters)
    }
    @Test fun clearingOneWordDoesNotChangeOtherWordsOrLearningMarks() = runBlocking {
        val dao = db.wordHistoryDao()
        dao.record(batch())
        dao.clearWord(engine.info.tag, "hablar")
        assertEquals(0, dao.stats(engine.info.tag, "hablar").first().encounters)
        assertEquals(1, dao.stats(engine.info.tag, "y").first().encounters)
        assertTrue(db.knownWordDao().observeWords(engine.info.tag).first().isEmpty())
    }
    @Test fun historySurvivesDatabaseReopen() = runBlocking {
        val name = "word-history-restart"
        app.deleteDatabase(name)
        fun open() = Room.databaseBuilder(app, LexiconDatabase::class.java, name).build()
        val initial = open()
        initial.wordHistoryDao().record(batch(source = EncounterSource("wiki:es:42", "Статья")), true)
        initial.close()
        val reopened = open()
        try { assertEquals(WordHistoryStats(2, 2, 1), reopened.wordHistoryDao().stats(engine.info.tag, "hablar").first()) }
        finally { reopened.close(); app.deleteDatabase(name) }
    }
    @Test fun chineseAndEnglishUseTheirOwnLemmasAndLanguageNamespaces() {
        val en = EnEngineTest.engine
        val text = "A teacher and teachers."
        val ch = ChapterBuilder.build(TxtBook(text.toByteArray(), "Test"), 0, en)
        val result = WordHistory.batch(EncounterSource("wiki:en:1", "English"), ch, listOf(WikiVisibleSpan(0, 0, text.length)), en)
        assertEquals(2, result.words.count { it.lemma == "teacher" })
        assertTrue(result.words.all { it.language == en.info.tag })
        val zh = ZhEngine(ZhSegmenterTest.dict, 0)
        val chinese = "他去图书馆。图书馆有书。"
        val zhChapter = ChapterBuilder.build(TxtBook(chinese.toByteArray(), "Test"), 0, zh)
        val words = WordHistory.batch(EncounterSource("wiki:zh:1", "中文"), zhChapter,
            listOf(WikiVisibleSpan(0, 0, chinese.length)), zh).words
        assertEquals(2, words.count { it.lemma == "图书馆" })
        assertTrue(words.all { it.language == zh.info.tag })
    }
    @Test fun onlyFullyVisibleWordsAndBoundedContextsAreRecorded() {
        val ch = chapter("Hablo y hablamos.")
        assertTrue(WordHistory.batch(source, ch, listOf(WikiVisibleSpan(0, 1, 4)), engine).words.isEmpty())
        assertTrue(WordHistory.batch(source, ch, listOf(WikiVisibleSpan(0, 0, 17)), engine, selection = true).words.isEmpty())
        val long = "hablamos ".repeat(1500)
        val result = WordHistory.batch(source, chapter(long), listOf(WikiVisibleSpan(0, 12000, 12050)), engine)
        assertTrue(result.words.isNotEmpty())
        assertTrue(result.fragments.all { it.text.length <= 8200 })
        result.words.forEach { word ->
            val fragment = result.fragments.first { it.id == word.fragmentId }
            assertEquals(word.original, fragment.text.substring(word.start - fragment.textStart, word.end - fragment.textStart))
        }
    }
    @Test fun viewportExcludesPrefetchedPagesAndOffscreenLines() {
        val viewport = EncounterViewport()
        viewport.viewport = Rect(0f, 0f, 300f, 200f)
        viewport.bounds["active"] = 0 to Rect(0f, -100f, 300f, 300f)
        viewport.lines["active"] = listOf(ReaderLineRange(0, 5, 0f, 20f), ReaderLineRange(6, 12, 100f, 120f), ReaderLineRange(13, 20, 350f, 370f))
        viewport.bounds["prefetch"] = 1 to Rect(300f, 0f, 600f, 200f)
        viewport.lines["prefetch"] = listOf(ReaderLineRange(0, 10, 0f, 20f))
        assertEquals(listOf(WikiVisibleSpan(0, 6, 12)), viewport.visible())
    }
    @Test fun failedHistoryReplacementRollsBackOldContextsAndCounters() = runBlocking {
        val dao = db.wordHistoryDao()
        dao.record(batch(), lookup = true)
        val saved = dao.snapshot()
        val broken = saved.copy(words = saved.words + saved.words.first().copy(id = "c".repeat(64), fragmentId = "missing"))
        assertTrue(runCatching { dao.replaceAll(broken) }.isFailure)
        assertEquals(saved, dao.snapshot())
        dao.replaceAll(com.hanreader.app.WordHistoryBackup(emptyList(), emptyList()))
        assertTrue(dao.allWords().isEmpty())
        assertTrue(dao.allFragments().isEmpty())
    }
    @Test fun revisitingSessionDoesNoLemmatizationAndDoesNotTouchTheDatabase() = runBlocking {
        var lemmas = 0
        val counting = object : com.hanreader.app.lang.LanguageModule by engine {
            override fun lemma(word: String): String { lemmas++; return engine.lemma(word) }
        }
        val ch = chapter("Hablo y hablamos.")
        val session = WordHistorySession(source, ch, counting)
        val spans = listOf(WikiVisibleSpan(0, 0, 17))
        assertTrue(session.record(spans, db.wordHistoryDao()) > 0)
        val before = lemmas
        val snapshot = db.wordHistoryDao().snapshot()
        repeat(100) { assertEquals(0, session.record(spans, db.wordHistoryDao())) }
        assertEquals(before, lemmas)
        assertEquals(snapshot, db.wordHistoryDao().snapshot())
    }
    @Test fun hugeParagraphWorkIsBoundedByVisibleWordsRatherThanChapterLength() = runBlocking {
        var reads = 0
        var lemmas = 0
        val tokens = object : AbstractList<RToken>() {
            override val size = 100_000
            override fun get(index: Int): RToken {
                reads++
                return RToken("hablamos", "hablar", com.hanreader.app.zh.TokenKind.HAN, index * 9, index * 9 + 8, true)
            }
        }
        val text = "hablamos ".repeat(100_000)
        val ch = ChapterContent(0, "Long", listOf(ReaderBlock.Text(text, false, tokens)), 0, text.length)
        val counting = object : com.hanreader.app.lang.LanguageModule by engine {
            override fun lemma(word: String): String { lemmas++; return engine.lemma(word) }
        }
        val session = WordHistorySession(source, ch, counting)
        val spans = listOf(WikiVisibleSpan(0, 99_900 * 9, 99_930 * 9))
        assertEquals(30, session.record(spans, db.wordHistoryDao()))
        assertTrue("Only binary search and the visible 30 tokens should be read: $reads", reads < 80)
        assertEquals(30, lemmas)
        repeat(100) { assertEquals(0, session.record(spans, db.wordHistoryDao())) }
        assertEquals(30, lemmas)
        assertEquals(30, db.wordHistoryDao().allWords().size)
    }
}
