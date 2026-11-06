package com.hanreader.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * and checks that cards, examples, sources and books survive and known_words works.
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
        }
        assertEquals(5, db.openHelper.readableDatabase.version)
        db.close()
    }
}
