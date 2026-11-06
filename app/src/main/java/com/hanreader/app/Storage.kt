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
    val blockIndex: Int = 0,
    /** Han word occurrences counted by the book analysis; 0 = not analysed yet. */
    @ColumnInfo(defaultValue = "0")
    val analyzedTokens: Int = 0
)

/** A word the reader marked «Это имя»: never taught, never underlined, kept whole by the segmenter. */
@Entity(tableName = "user_names")
data class UserName(
    @PrimaryKey val word: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface UserNameDao {
    @Query("SELECT word FROM user_names")
    fun observeWords(): Flow<List<String>>

    @Query("SELECT * FROM user_names ORDER BY word")
    suspend fun all(): List<UserName>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(n: UserName)

    @Query("DELETE FROM user_names WHERE word = :word")
    suspend fun remove(word: String)

    @Query("DELETE FROM user_names")
    suspend fun removeAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(list: List<UserName>)
}

/** A bookmark: a paragraph of a chapter, with the start of its text to recognise it. */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["bookId"])]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val blockIndex: Int,
    val chapterTitle: String,
    val snippet: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, blockIndex")
    fun observe(bookId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insert(b: Bookmark): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

/** How often a word occurs in a book, from the one-time book analysis. */
@Entity(
    tableName = "book_words",
    primaryKeys = ["bookId", "word"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BookWord(
    val bookId: Long,
    val word: String,
    val count: Int,
    /** First spine item where the word occurs: the source of its example sentence. */
    val firstChapter: Int,
    /** «Не учить» for this book (names, words the reader does not want to study). */
    @ColumnInfo(defaultValue = "0")
    val ignored: Boolean = false
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

/**
 * A word the reader treats as known: no pinyin in "unknown only" mode, not counted as new.
 * [source] is "manual" (tapped «Знаю») or "hsk3:N" / "hsk2:N" (bulk-marked by level).
 */
@Entity(tableName = "known_words")
data class KnownWord(
    @PrimaryKey val word: String,
    val source: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface KnownWordDao {
    @Query("SELECT word FROM known_words")
    fun observeWords(): Flow<List<String>>

    @Query("SELECT * FROM known_words ORDER BY word")
    suspend fun all(): List<KnownWord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(word: KnownWord)

    /** Bulk marking never overrides a word the user marked by hand. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putAllIfAbsent(words: List<KnownWord>): List<Long>

    @Query("DELETE FROM known_words WHERE word = :word")
    suspend fun remove(word: String)

    @Query("DELETE FROM known_words WHERE source LIKE 'hsk%'")
    suspend fun removeLevelMarks()

    @Query("DELETE FROM known_words")
    suspend fun removeAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(words: List<KnownWord>)

    /** Replace the HSK marks with words up to the chosen level; manual marks stay. */
    @Transaction
    suspend fun setLevelMarks(words: List<KnownWord>): Int {
        removeLevelMarks()
        return putAllIfAbsent(words).count { it > 0 }
    }

    @Transaction
    suspend fun replaceAll(words: List<KnownWord>) {
        removeAll()
        putAll(words)
    }
}

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

    @Query("SELECT * FROM book_words WHERE bookId = :bookId")
    fun observeWords(bookId: Long): Flow<List<BookWord>>

    @Query("DELETE FROM book_words WHERE bookId = :bookId")
    suspend fun deleteWords(bookId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWords(words: List<BookWord>)

    @Query("UPDATE books SET analyzedTokens = :tokens WHERE id = :id")
    suspend fun setAnalyzed(id: Long, tokens: Int)

    @Query("UPDATE book_words SET ignored = :ignored WHERE bookId = :bookId AND word = :word")
    suspend fun setIgnored(bookId: Long, word: String, ignored: Boolean)

    /** Replace the word counts of a book in one go; ignore marks survive a re-analysis. */
    @Transaction
    suspend fun saveAnalysis(bookId: Long, words: List<BookWord>, tokens: Int) {
        val ignored = ignoredWords(bookId).toHashSet()
        deleteWords(bookId)
        words.chunked(2_000).forEach { chunk ->
            insertWords(chunk.map { if (it.word in ignored) it.copy(ignored = true) else it })
        }
        setAnalyzed(bookId, tokens)
    }

    @Query("SELECT word FROM book_words WHERE bookId = :bookId AND ignored = 1")
    suspend fun ignoredWords(bookId: Long): List<String>
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

// Version 4 (HanReader 0.1.0): books and word_sources. HanReader uses its own database file,
// so no database of version 3 exists under this name; Lexicon data arrives through backups.
// Version 5 (0.2.0): known_words. Schemas are exported to app/schemas for migration checks.
// Version 6 (0.3.0): book_words and books.analyzedTokens (book vocabulary analysis).
// Version 7 (0.7.0): bookmarks. Version 8 (0.9.2): user_names.
@Database(
    entities = [
        WordCard::class, CardExample::class, BookEntity::class, WordSource::class, KnownWord::class, BookWord::class,
        Bookmark::class, UserName::class
    ],
    version = 8,
    exportSchema = true
)
abstract class LexiconDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao
    abstract fun bookDao(): BookDao
    abstract fun knownWordDao(): KnownWordDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun userNameDao(): UserNameDao

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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `known_words` (`word` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`word`))"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN analyzedTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL(BOOK_WORDS_SQL)
            }
        }

        // Copied from app/schemas/.../6.json; MigrationTest checks it against the entity.
        private const val BOOK_WORDS_SQL = "CREATE TABLE IF NOT EXISTS `book_words` (`bookId` INTEGER NOT NULL, " +
            "`word` TEXT NOT NULL, `count` INTEGER NOT NULL, `firstChapter` INTEGER NOT NULL, " +
            "`ignored` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`bookId`, `word`), " +
            "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(BOOKMARKS_SQL)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
            }
        }

        // Copied from app/schemas/.../7.json; MigrationTest checks it against the entity.
        private const val BOOKMARKS_SQL = "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, `blockIndex` INTEGER NOT NULL, " +
            "`chapterTitle` TEXT NOT NULL, `snippet` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_names` (`word` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`word`))"
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)

        fun get(context: Context): LexiconDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, LexiconDatabase::class.java, "hanreader.db"
                ).addMigrations(*MIGRATIONS).build().also { instance = it }
            }
    }
}
