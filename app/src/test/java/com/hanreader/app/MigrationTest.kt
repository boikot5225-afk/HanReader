package com.hanreader.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Upgrades a database created exactly as HanReader 0.1.0 (Room v4, from app/schemas/.../4.json)
 * through every later version and checks that cards, examples, sources and books survive and
 * known_words / book_words work.
 * Room validates the migrated schema against the v5 entities when the database opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun createV4(file: File) {
        val schema = JSONObject(File("schemas/com.hanreader.app.LexiconDatabase/4.json").readText())
            .getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices") ?: continue
            for (k in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '${schema.getString("identityHash")}')")
        db.execSQL(
            "INSERT INTO cards (id, language, word, reading, translation, example, exampleTranslation, createdAt, " +
                "nextReviewAt, successfulReviews, intervalDays) VALUES (1, 'zh-CN', '终于', 'zhōng yú', 'finally', " +
                "'他终于回来了。', '', 1000, 2000, 3, 40)"
        )
        db.execSQL("INSERT INTO card_examples (cardId, example, exampleTranslation) VALUES (1, '他终于回来了。', '')")
        db.execSQL(
            "INSERT INTO books (id, title, author, fileName, chapterCount, addedAt, lastOpenedAt, chapterIndex, blockIndex) " +
                "VALUES (7, '书', '作者', 'book-1.epub', 12, 1, 2, 5, 33)"
        )
        db.execSQL(
            "INSERT INTO word_sources (cardId, bookId, bookTitle, chapterIndex, chapterTitle, sentence, createdAt) " +
                "VALUES (1, 7, '书', 5, '第五章', '他终于回来了。', 3)"
        )
        db.version = 4
        db.close()
    }

    @Test
    fun upgradesFromHanReader010() {
        val file = app.getDatabasePath("migration-test.db")
        file.parentFile?.mkdirs()
        file.delete()
        createV4(file)
        val db = Room.databaseBuilder(app, LexiconDatabase::class.java, file.name)
            .addMigrations(*LexiconDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            val card = db.cardDao().allCards().single()
            assertEquals("终于", card.word)
            assertEquals(40, card.intervalDays)
            assertEquals(1, db.cardDao().allExamples().size)
            val book = db.bookDao().get(7)!!
            assertEquals(5, book.chapterIndex)
            assertEquals(33, book.blockIndex)
            db.knownWordDao().put(KnownWord("我", "manual"))
            assertEquals(listOf("我"), db.knownWordDao().all().map { it.word })
            // v6: analysis columns and table
            assertEquals(0, book.analyzedTokens)
            db.bookDao().saveAnalysis(7, listOf(BookWord(7, "终于", 3, 5)), 10)
            assertEquals(10, db.bookDao().get(7)!!.analyzedTokens)
            // v9: the spot inside the paragraph
            assertEquals(0, book.charOffset)
            db.bookDao().savePosition(7, 5, 33, 120)
            assertEquals(120, db.bookDao().get(7)!!.charOffset)
            db.bookDao().setIgnored(7, "终于", true)
            db.bookDao().saveAnalysis(7, listOf(BookWord(7, "终于", 4, 5)), 11)
            assertEquals(listOf("终于"), db.bookDao().ignoredWords(7)) // ignore marks survive re-analysis
            // v10: highlights, removed with their book
            db.highlightDao().insert(Highlight(bookId = 7, chapterIndex = 5, blockIndex = 12, startChar = 0, endChar = 4, color = 1, text = "他终于回", chapterTitle = "第五章"))
            assertEquals("他终于回", db.highlightDao().observe(7).first().single().text)
            // v11: reading by day, book progress removed with its book
            db.statsDao().addReading(20261106, 60_000, 250)
            assertEquals(250, db.statsDao().allDays().single().readChars)
            db.statsDao().putProgress(BookProgress(7, 20261106, 42))
            // v12: the reader's own meanings
            db.userMeaningDao().put(UserMeaning("端", "держать в руках"))
            assertEquals("держать в руках", db.userMeaningDao().all().single().meaning)
            // v7: bookmarks, removed with their book
            db.bookmarkDao().insert(Bookmark(bookId = 7, chapterIndex = 5, blockIndex = 12, chapterTitle = "第五章", snippet = "他终于回来了"))
            assertEquals(1, db.bookmarkDao().observe(7).first().size)
            db.bookDao().delete(7)
            assertEquals(0, db.bookmarkDao().observe(7).first().size)
            assertEquals(0, db.highlightDao().observe(7).first().size)
            assertEquals(0, db.statsDao().allProgress().size)
            // v8: names marked by the reader
            db.userNameDao().put(UserName("雷蒙德"))
            assertEquals(listOf("雷蒙德"), db.userNameDao().all().map { it.word })
            assertEquals(emptyList<String>(), db.bookDao().ignoredWords(7)) // cascade
        }
        assertEquals(12, db.openHelper.readableDatabase.version)
        db.close()
    }
}
