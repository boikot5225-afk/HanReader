package com.hanreader.app.reader

import androidx.room.*
import com.hanreader.app.BookEntity
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One shared text fragment, rather than a sentence copied for every word. */
@Entity(tableName = "reading_fragments", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class ReadingFragment(@PrimaryKey val id: String, val sourceKey: String, val bookId: Long?,
    val title: String, val chapterTitle: String, val chapter: Int, val block: Int, val textStart: Int, val text: String)

@Entity(tableName = "word_encounters", foreignKeys = [ForeignKey(entity = ReadingFragment::class,
    parentColumns = ["id"], childColumns = ["fragmentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("fragmentId"), Index(value = ["language", "lemma"])])
data class WordEncounter(@PrimaryKey val id: String, val fragmentId: String, val language: String,
    val lemma: String, val original: String, val start: Int, val end: Int, val firstSeen: Long, val lookups: Int = 0)

data class WordHistoryStats(val encounters: Int, val lookups: Int, val sources: Int)
data class WordHistoryContext(val original: String, val start: Int, val end: Int, val firstSeen: Long,
    val lookups: Int, val title: String, val chapterTitle: String, val textStart: Int, val text: String)

@Dao
abstract class WordHistoryDao {
    @Query("""
        SELECT lemma, COUNT(*) AS encounters, COALESCE(SUM(lookups), 0) AS lookups,
            COUNT(DISTINCT date(firstSeen / 1000, 'unixepoch', 'localtime')) AS days
        FROM word_encounters
        WHERE language = :language AND lemma IN (:lemmas)
        GROUP BY lemma
    """)
    abstract fun familiarity(language: String, lemmas: List<String>): Flow<List<WordFamiliarity>>

    @Query("SELECT w.*, f.title, f.chapterTitle, f.sourceKey, f.textStart, f.text FROM word_encounters w JOIN reading_fragments f ON f.id = w.fragmentId WHERE w.language = :language AND w.lemma IN (:lemmas) ORDER BY w.firstSeen DESC, w.id LIMIT 60")
    abstract fun studyContexts(language: String, lemmas: List<String>): Flow<List<com.hanreader.app.StudyEncounter>>
    @Query("SELECT w.*, f.title, f.chapterTitle, f.sourceKey, f.textStart, f.text FROM word_encounters w JOIN reading_fragments f ON f.id = w.fragmentId WHERE w.id = :id")
    abstract suspend fun studyContext(id: String): com.hanreader.app.StudyEncounter?
    @Query("SELECT * FROM reading_fragments ORDER BY id") abstract suspend fun allFragments(): List<ReadingFragment>
    @Query("SELECT * FROM word_encounters ORDER BY id") abstract suspend fun allWords(): List<WordEncounter>
    @Query("DELETE FROM reading_fragments") abstract suspend fun clearAll()
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun restoreFragments(rows: List<ReadingFragment>)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun restoreWords(rows: List<WordEncounter>)
    @Transaction open suspend fun snapshot(): com.hanreader.app.WordHistoryBackup =
        com.hanreader.app.WordHistoryBackup(allFragments(), allWords())
    @Transaction open suspend fun replaceAll(history: com.hanreader.app.WordHistoryBackup) {
        clearAll()
        history.fragments.chunked(1000).forEach { restoreFragments(it) }
        history.words.chunked(2000).forEach { restoreWords(it) }
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertFragments(rows: List<ReadingFragment>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertEncounters(rows: List<WordEncounter>)
    @Query("UPDATE word_encounters SET lookups = lookups + 1 WHERE id = :id") abstract suspend fun incrementLookup(id: String)
    @Transaction open suspend fun record(batch: EncounterBatch, lookup: Boolean = false) {
        insertFragments(batch.fragments)
        insertEncounters(batch.words)
        if (lookup) batch.words.forEach { incrementLookup(it.id) }
    }
    @Query("SELECT COUNT(*) AS encounters, COALESCE(SUM(w.lookups), 0) AS lookups, COUNT(DISTINCT f.sourceKey) AS sources FROM word_encounters w JOIN reading_fragments f ON f.id = w.fragmentId WHERE w.language = :language AND w.lemma = :lemma")
    abstract fun stats(language: String, lemma: String): Flow<WordHistoryStats>
    @Query("SELECT w.original, w.start, w.end, w.firstSeen, w.lookups, f.title, f.chapterTitle, f.textStart, f.text FROM word_encounters w JOIN reading_fragments f ON f.id = w.fragmentId WHERE w.language = :language AND w.lemma = :lemma ORDER BY w.firstSeen DESC, w.id LIMIT 30")
    abstract fun contexts(language: String, lemma: String): Flow<List<WordHistoryContext>>
    @Query("DELETE FROM word_encounters WHERE language = :language AND lemma = :lemma") abstract suspend fun deleteWord(language: String, lemma: String)
    @Query("DELETE FROM reading_fragments WHERE id NOT IN (SELECT fragmentId FROM word_encounters)") abstract suspend fun pruneFragments()
    @Transaction open suspend fun clearWord(language: String, lemma: String) { deleteWord(language, lemma); pruneFragments() }
}

data class EncounterSource(val key: String, val title: String, val bookId: Long? = null)
data class EncounterBatch(val fragments: List<ReadingFragment>, val words: List<WordEncounter>)

internal object WordHistory {
    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    fun wordId(fragmentId: String, start: Int, end: Int, lemma: String) = hash("$fragmentId\u0000$start\u0000$end\u0000$lemma")

    /** Stable source offsets and hashes; pages, fonts, hints and images are not part of the key. */
    fun batch(source: EncounterSource, chapter: ChapterContent, spans: List<WikiVisibleSpan>,
        engine: LanguageModule, now: Long = System.currentTimeMillis(), selection: Boolean = false,
        textHashes: MutableMap<Int, String>? = null,
        skip: (Int, Int, Int) -> Boolean = { _, _, _ -> false }): EncounterBatch {
        val fragments = LinkedHashMap<String, ReadingFragment>()
        val words = LinkedHashMap<String, WordEncounter>()
        for (span in spans) {
            val block = chapter.blocks.getOrNull(span.block) as? ReaderBlock.Text ?: continue
            if (block.heading) continue
            if (selection && (span.start < 0 || span.end > block.text.length || span.end <= span.start || span.end - span.start > 180)) continue
            // Tokens are in source order. A screenful near the end of a huge paragraph must not
            // scan/lemmatise every preceding token on each page turn.
            var lo = 0
            var hi = block.tokens.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (block.tokens[mid].end <= span.start) lo = mid + 1 else hi = mid
            }
            val candidates = ArrayList<RToken>()
            var index = lo
            while (index < block.tokens.size) {
                val token = block.tokens[index++]
                if (token.start >= span.end) break
                if (token.kind == TokenKind.HAN && token.start >= span.start && token.end <= span.end &&
                    (selection || !skip(span.block, token.start, token.end))) candidates += token
            }
            if (candidates.isEmpty()) continue
            val textHash = textHashes?.getOrPut(span.block) { hash(block.text) } ?: hash(block.text)
            val fragmentKeys = HashMap<Int, String>()
            fun add(original: String, lemma: String, start: Int, end: Int) {
                if (lemma.isBlank() || lemma.length > 180 || end <= start) return
                val chunk = start / 8000 * 8000
                val id = fragmentKeys.getOrPut(chunk) { hash("v1\u0000${source.key}\u0000${engine.info.tag}\u0000${chapter.index}\u0000${span.block}\u0000$textHash\u0000$chunk") }
                fragments.getOrPut(id) { ReadingFragment(id, source.key, source.bookId, source.title,
                    chapter.title, chapter.index, span.block, chunk,
                    block.text.substring(chunk, minOf(block.text.length, maxOf(chunk + 8200, end)))) }
                val wordId = wordId(id, start, end, lemma)
                words[wordId] = WordEncounter(wordId, id, engine.info.tag, lemma, original, start, end, now)
            }
            if (selection) {
                val tokens = candidates
                if (tokens.isEmpty() || tokens.size > 6 || tokens.first().start != span.start || tokens.last().end != span.end) continue
                val original = block.text.substring(span.start, span.end)
                if (original.length > 180) continue
                add(original, if (tokens.size == 1) engine.lemma(original) else original.lowercase(), span.start, span.end)
            } else for (token in candidates) {
                add(token.text, engine.lemma(token.text), token.start, token.end)
            }
        }
        return EncounterBatch(fragments.values.toList(), words.values.toList())
    }
}

/** A chapter-local cache of source positions. Revisited words require no hashes, lemmas or SQL. */
internal class WordHistorySession(private val source: EncounterSource, private val chapter: ChapterContent,
    private val engine: LanguageModule) {
    private val mutex = Mutex()
    private val hashes = HashMap<Int, String>()
    private val seen = HashMap<Int, MutableSet<Long>>()
    private fun position(start: Int, end: Int) = (start.toLong() shl 32) or (end.toLong() and 0xffffffffL)
    suspend fun record(spans: List<WikiVisibleSpan>, dao: WordHistoryDao): Int = mutex.withLock {
        val batch = WordHistory.batch(source, chapter, spans, engine, textHashes = hashes,
            skip = { block, start, end -> seen[block]?.contains(position(start, end)) == true })
        if (batch.words.isEmpty()) return@withLock 0
        dao.record(batch)
        val blocks = batch.fragments.associate { it.id to it.block }
        for (word in batch.words) seen.getOrPut(blocks.getValue(word.fragmentId)) { HashSet() }
            .add(position(word.start, word.end))
        batch.words.size
    }
}
