package com.hanreader.app.ai

import androidx.room.*
import com.hanreader.app.BookEntity
import kotlinx.coroutines.flow.Flow

/** A checked occurrence, not a dictionary entry. Offsets are UTF-16 in the source paragraph. */
@Entity(tableName = "ai_checks", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")])
data class AiCheck(
    @PrimaryKey val cacheKey: String,
    val bookId: Long,
    val chapter: Int,
    val block: Int,
    val contextHash: String,
    val termStart: Int,
    val termEnd: Int,
    val start: Int,
    val end: Int,
    val original: String,
    val base: String,
    val translation: String?,
    val explanation: String?,
    val confidence: String,
    /** checked, proposed, accepted, rejected */
    val state: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface AiCheckDao {
    @Query("SELECT * FROM ai_checks ORDER BY createdAt, cacheKey")
    suspend fun all(): List<AiCheck>
    /** Restore atomically with the books, preserving cache identities and explicit rejections. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun restore(rows: List<AiCheck>)
    @Query("SELECT * FROM ai_checks WHERE bookId = :book ORDER BY createdAt, cacheKey")
    fun observe(book: Long): Flow<List<AiCheck>>
    @Query("SELECT * FROM ai_checks WHERE bookId = :book AND chapter = :chapter ORDER BY createdAt, cacheKey")
    fun observeChapter(book: Long, chapter: Int): Flow<List<AiCheck>>
    @Query("SELECT * FROM ai_checks WHERE cacheKey = :key")
    suspend fun get(key: String): AiCheck?
    @Query("SELECT * FROM ai_checks WHERE bookId = :book AND contextHash = :context")
    suspend fun context(book: Long, context: String): List<AiCheck>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun put(rows: List<AiCheck>)
    @Query("UPDATE ai_checks SET state = :state WHERE cacheKey = :key")
    suspend fun state(key: String, state: String)
    @Query("UPDATE ai_checks SET state = :state WHERE bookId = :book AND contextHash = :context AND start = :start AND end = :end AND translation IS NOT NULL")
    suspend fun stateSpan(book: Long, context: String, start: Int, end: Int, state: String)
    @Query("UPDATE ai_checks SET state = CASE WHEN translation = :translation THEN 'accepted' ELSE 'rejected' END WHERE bookId = :book AND contextHash = :context AND start = :start AND end = :end AND translation IS NOT NULL")
    suspend fun acceptSpan(book: Long, context: String, start: Int, end: Int, translation: String)
    @Query("UPDATE ai_checks SET state = 'rejected' WHERE bookId = :book AND contextHash = :context AND state = 'accepted' AND start < :end AND end > :start AND NOT (start = :start AND end = :end)")
    suspend fun rejectOverlaps(book: Long, context: String, start: Int, end: Int)
    /** Accepted corrections and explicit rejections survive clearing the check cache. */
    @Query("DELETE FROM ai_checks WHERE state IN ('checked', 'proposed')")
    suspend fun clearCache()
    @Query("DELETE FROM ai_checks")
    suspend fun clearAll()
}
