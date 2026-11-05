package com.hanreader.app

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Transaction
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "cards",
    indices = [Index(value = ["language", "word", "translation"], unique = true)]
)
data class WordCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val language: String,
    val word: String,
    val reading: String,
    val translation: String,
    val example: String,
    val exampleTranslation: String,
    val createdAt: Long = System.currentTimeMillis(),
    val nextReviewAt: Long = 0,
    val successfulReviews: Int = 0,
    val intervalDays: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val recognitionLapses: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val writingNextReviewAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val writingSuccessfulReviews: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val writingIntervalDays: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val writingLapses: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val introducedAt: Long = 0
)

@Entity(
    tableName = "card_examples",
    foreignKeys = [
        ForeignKey(
            entity = WordCard::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["cardId"]),
        Index(value = ["cardId", "example", "exampleTranslation"], unique = true)
    ]
)
data class CardExample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val example: String,
    val exampleTranslation: String
)

/** An imported EPUB. The file lives in the app's private storage under books/[fileName]. */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val fileName: String,
    val chapterCount: Int,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0,
    /** Reading position: spine item and first visible block. */
    val chapterIndex: Int = 0,
    val blockIndex: Int = 0
)

/**
 * Where a word was met: the sentence, the book and the chapter. One row per save from the reader.
 * The book reference is plain data (no foreign key), so sources survive deleting the book.
 */
@Entity(
    tableName = "word_sources",
    foreignKeys = [
        ForeignKey(
            entity = WordCard::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["cardId"]), Index(value = ["bookId"])]
)
data class WordSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val bookId: Long,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val sentence: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** A word saved from the reader, with its context. */
data class ReaderSave(
    val word: String,
    val reading: String,
    val translation: String,
    val sentence: String,
    val bookId: Long,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY CASE WHEN lastOpenedAt > 0 THEN lastOpenedAt ELSE addedAt END DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Query("UPDATE books SET chapterIndex = :chapter, blockIndex = :block, lastOpenedAt = :at WHERE id = :id")
    suspend fun savePosition(id: Long, chapter: Int, block: Int, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

data class ImportStats(
    val added: Int,
    val examplesAdded: Int,
    val skipped: Int
)

@Dao
interface CardDao {
    @Query("SELECT * FROM cards WHERE language = :language ORDER BY createdAt DESC, id DESC")
    fun observeByLanguage(language: String): Flow<List<WordCard>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(cards: List<WordCard>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOne(card: WordCard): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExample(row: CardExample): Long

    @Query("SELECT id FROM cards WHERE language = :language AND word = :word AND translation = :translation LIMIT 1")
    suspend fun findCardId(language: String, word: String, translation: String): Long?

    @Query("SELECT card_examples.* FROM card_examples INNER JOIN cards ON cards.id = card_examples.cardId WHERE cards.language = :language ORDER BY card_examples.id")
    fun observeExamples(language: String): Flow<List<CardExample>>

    @Transaction
    suspend fun importCards(language: String, rows: List<ImportCard>): ImportStats {
        var added = 0
        var examplesAdded = 0
        var skipped = 0
        for (row in rows) {
            val created = insertOne(
                WordCard(
                    language = language,
                    word = row.word,
                    reading = row.reading,
                    translation = row.translation,
                    example = row.example,
                    exampleTranslation = row.exampleTranslation
                )
            )
            val cardId = if (created > 0) {
                added++
                created
            } else {
                findCardId(language, row.word, row.translation)
                    ?: error("Не удалось найти уже существующее слово")
            }
            val contextId = insertExample(
                CardExample(
                    cardId = cardId,
                    example = row.example,
                    exampleTranslation = row.exampleTranslation
                )
            )
            if (contextId > 0) examplesAdded++ else skipped++
        }
        return ImportStats(added, examplesAdded, skipped)
    }

    @Transaction
    suspend fun addContext(cardId: Long, text: String, translation: String): Boolean {
        require(text.isNotBlank() && translation.isNotBlank())
        return insertExample(
            CardExample(
                cardId = cardId,
                example = text.trim(),
                exampleTranslation = translation.trim()
            )
        ) > 0L
    }

    @Query(
        "SELECT COUNT(*) FROM cards WHERE language = :language AND word = :word " +
            "AND translation = :translation AND id != :id"
    )
    suspend fun countOtherCards(id: Long, language: String, word: String, translation: String): Int

    @Query("UPDATE cards SET word = :word, reading = :reading, translation = :translation WHERE id = :id")
    suspend fun updateCardText(id: Long, word: String, reading: String, translation: String)

    /** Edit the text of a card, keeping its progress. False if another card already has this word and translation. */
    @Transaction
    suspend fun editCard(id: Long, language: String, word: String, reading: String, translation: String): Boolean {
        require(word.isNotBlank() && translation.isNotBlank())
        if (countOtherCards(id, language, word.trim(), translation.trim()) > 0) return false
        updateCardText(id, word.trim(), reading.trim(), translation.trim())
        return true
    }

    @Query(
        "SELECT COUNT(*) FROM card_examples WHERE cardId = :cardId AND example = :example " +
            "AND exampleTranslation = :translation AND id != :id"
    )
    suspend fun countOtherExamples(id: Long, cardId: Long, example: String, translation: String): Int

    @Query("UPDATE card_examples SET example = :example, exampleTranslation = :translation WHERE id = :id")
    suspend fun updateExampleText(id: Long, example: String, translation: String)

    @Query("SELECT COUNT(*) FROM card_examples WHERE cardId = :cardId")
    suspend fun countExamples(cardId: Long): Int

    @Query("DELETE FROM card_examples WHERE id = :id")
    suspend fun deleteExampleRow(id: Long)

    /**
     * Edit one example. exampleId 0 is the legacy example stored only on the card:
     * it becomes a real row. False if the card already has the same example.
     */
    @Transaction
    suspend fun editExample(cardId: Long, exampleId: Long, text: String, translation: String): Boolean {
        require(text.isNotBlank() && translation.isNotBlank())
        val example = text.trim()
        val exampleTranslation = translation.trim()
        if (countOtherExamples(exampleId, cardId, example, exampleTranslation) > 0) return false
        if (exampleId == 0L) {
            return insertExample(CardExample(cardId = cardId, example = example, exampleTranslation = exampleTranslation)) > 0L
        }
        updateExampleText(exampleId, example, exampleTranslation)
        return true
    }

    /** Every word keeps at least one example. False if this is the last one. */
    @Transaction
    suspend fun deleteExample(cardId: Long, exampleId: Long): Boolean {
        if (exampleId == 0L || countExamples(cardId) <= 1) return false
        deleteExampleRow(exampleId)
        return true
    }

    @Insert
    suspend fun insertSource(row: WordSource): Long

    @Query("SELECT COUNT(*) FROM word_sources WHERE bookId = :bookId")
    fun observeSavedFromBook(bookId: Long): Flow<Int>

    /**
     * Save a word met while reading. An existing card (same word and translation) keeps its
     * progress and gains the sentence as one more example; the source row records book and chapter.
     * Returns true when a new card was created.
     */
    @Transaction
    suspend fun saveFromReader(language: String, save: ReaderSave): Boolean {
        val created = insertOne(
            WordCard(
                language = language,
                word = save.word,
                reading = save.reading,
                translation = save.translation,
                example = save.sentence,
                exampleTranslation = ""
            )
        )
        val cardId = if (created > 0) created else
            findCardId(language, save.word, save.translation) ?: error("Не удалось найти уже существующее слово")
        insertExample(CardExample(cardId = cardId, example = save.sentence, exampleTranslation = ""))
        insertSource(
            WordSource(
                cardId = cardId,
                bookId = save.bookId,
                bookTitle = save.bookTitle,
                chapterIndex = save.chapterIndex,
                chapterTitle = save.chapterTitle,
                sentence = save.sentence
            )
        )
        return created > 0
    }

    @Query("SELECT * FROM cards ORDER BY id")
    suspend fun allCards(): List<WordCard>

    @Query("SELECT * FROM card_examples ORDER BY id")
    suspend fun allExamples(): List<CardExample>

    @Query("DELETE FROM cards")
    suspend fun deleteAllCards()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCardsStrict(cards: List<WordCard>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExamplesStrict(rows: List<CardExample>)

    /** Replace the whole dictionary with a backup. Any error rolls everything back. */
    @Transaction
    suspend fun replaceAll(cards: List<WordCard>, examples: List<CardExample>) {
        deleteAllCards()
        insertCardsStrict(cards)
        insertExamplesStrict(examples)
    }

    @Update
    suspend fun update(card: WordCard)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun delete(id: Long)
}

// Version 4 (HanReader): books and word_sources. HanReader uses its own database file,
// so no database of version 3 exists under this name; Lexicon data arrives through backups.
@Database(
    entities = [WordCard::class, CardExample::class, BookEntity::class, WordSource::class],
    version = 4,
    exportSchema = false
)
abstract class LexiconDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao
    abstract fun bookDao(): BookDao

    companion object {
        @Volatile private var instance: LexiconDatabase? = null
        // Keep old recognition progress and all imported cards when upgrading from 0.2.
        // Writing history starts empty and is scheduled independently.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN writingNextReviewAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cards ADD COLUMN writingSuccessfulReviews INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cards ADD COLUMN writingIntervalDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cards ADD COLUMN introducedAt INTEGER NOT NULL DEFAULT 0")
                // Previously reviewed cards are no longer considered unintroduced.
                db.execSQL(
                    "UPDATE cards SET introducedAt = createdAt " +
                        "WHERE nextReviewAt > 0 OR successfulReviews > 0"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN recognitionLapses INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cards ADD COLUMN writingLapses INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS card_examples (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "cardId INTEGER NOT NULL, " +
                        "example TEXT NOT NULL, " +
                        "exampleTranslation TEXT NOT NULL, " +
                        "FOREIGN KEY(cardId) REFERENCES cards(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_card_examples_cardId ON card_examples(cardId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_card_examples_cardId_example_exampleTranslation " +
                        "ON card_examples(cardId, example, exampleTranslation)"
                )
                db.execSQL(
                    "INSERT INTO card_examples (cardId, example, exampleTranslation) " +
                        "SELECT id, example, exampleTranslation FROM cards"
                )
            }
        }

        fun get(context: Context): LexiconDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, LexiconDatabase::class.java, "hanreader.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
