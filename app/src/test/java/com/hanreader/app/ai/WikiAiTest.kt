package com.hanreader.app.ai

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.*
import com.hanreader.app.reader.*
import com.hanreader.app.wiki.*
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WikiAiTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: LexiconDatabase
    private lateinit var repo: AiRepository
    private val fileName = "wiki-ai-test.db"
    private var calls = 0
    private var config = AiConfig(enabled = true, automatic = true, consent = true, hasKey = true)
    private var gate: CompletableDeferred<Unit>? = null
    private var fail = false
    private var malformed = false
    private var confidence = "high"
    private val article = WikiArticle("es", 42, "Ciencia", "Introducción.", 321, 1234, false)
    private val appearance = Appearance(gloss = GlossMode.UNKNOWN)
    private val engine = object : LanguageModule {
        override val info = Languages.SPANISH
        override fun cardTranslation(word: String, reading: String?) = glossShort(word, reading)
        override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText = { text ->
            PreparedText(text, text, Regex("\\p{L}+|\\s+|[^\\p{L}\\s]").findAll(text).map {
                RToken(it.value, "", if (it.value.any(Char::isLetter)) TokenKind.HAN else if (it.value.isBlank()) TokenKind.SPACE else TokenKind.PUNCT,
                    it.range.first, it.range.last + 1, true)
            }.toList())
        }
        override fun glossShort(word: String, reading: String?) = when (word) {
            "pista" -> "дорожка"; "periódicos" -> "газеты"; "se" -> "себя"; "van" -> "идут"; "identificando" -> "отождествляя"; else -> null
        }
    }
    private val client = AiClient { _, _, payload ->
        calls++; gate?.await(); check(!fail)
        val text = payload.getString("paragraph")
        val results = JSONArray()
        val terms = payload.getJSONArray("terms")
        for (i in 0 until terms.length()) {
            val term = terms.getJSONObject(i); val word = term.getString("original")
            val phrase = word in setOf("se", "van", "identificando") && "se van identificando" in text
            val translation = when { phrase -> "постепенно отождествляются"; word == "pista" && "periódicos" in text -> "улика"; word == "Desconocido" -> "неизвестный"; else -> null }
            results.put(JSONObject().put("term_id", term.getString("term_id")).put("original", word)
                .put("current_translation", term.getString("current_translation")).put("needs_correction", translation != null)
                .put("suggested_translation", translation ?: JSONObject.NULL).put("explanation", JSONObject.NULL).put("confidence", confidence)
                .apply { if (phrase) put("span", JSONObject().put("text", "se van identificando")) })
        }
        AiReply(if (malformed) "{truncated" else JSONObject().put("results", results).toString(), 50, true)
    }
    private fun open() = Room.databaseBuilder(app, LexiconDatabase::class.java, fileName).addMigrations(*LexiconDatabase.MIGRATIONS).allowMainThreadQueries().build()
    private fun repository() = AiRepository(db, client, { config }, { "mock-key-not-an-account" })
    private suspend fun reading(text: String = "Buscaba una pista en los periódicos.", a: WikiArticle = article, images: List<WikiImage> = emptyList()) =
        WikiReading.prepare(WikiDocument(a, listOf(WikiBlock(text)), images), engine, TextOptions())
    private fun fragment(r: WikiReading, meanings: Map<String, String> = emptyMap()) = WikiAiContext.build(r, 0, engine, meanings, appearance)!!
    @Before fun setup() { app.deleteDatabase(fileName); db = open(); repo = repository() }
    @After fun cleanup() { db.close(); app.deleteDatabase(fileName) }

    @Test fun correctionIsLocalAndCachedAcrossRestartFontsAndPicturesWithoutAddingABook() = runBlocking {
        val reading = reading(); val fragment = fragment(reading)
        repo.attachArticle(fragment.articleKey!!)
        val rows = repo.check(fragment, false) { true }
        assertEquals("улика", rows.single { it.original == "pista" }.translation)
        assertEquals("checked", rows.single { it.original == "periódicos" }.state)
        assertTrue(rows.all { it.bookId == null && it.articleKey == reading.document.key })
        assertTrue(db.bookDao().all().isEmpty()); assertTrue(db.aiCheckDao().forBackup().isEmpty())
        val images = listOf(WikiImage("https://upload.wikimedia.org/wikipedia/commons/a/ab/Photo.png", "File:Photo.png", "Фото", 200, 100, 0))
        val illustrated = reading(images = images)
        val same = WikiAiContext.build(illustrated, 0, engine, emptyMap(), appearance.copy(fontSize = 30, margin = 40))!!
        assertEquals(fragment.contextHash, same.contextHash)
        db.close(); db = open(); repo = repository()
        val reopened = repo.check(same, false) { true }
        assertEquals(1, calls); assertEquals(rows, reopened)
        val glosses = hashMapOf<String, String>()
        AiContext.overlay(glosses, illustrated.textChapter.blocks[0] as ReaderBlock.Text, 0, reopened, false)
        assertEquals("улика", glosses["@0:${fragment.text.indexOf("pista")}"])
    }
    @Test fun articlesRevisionsAndBookChecksAreIndependent() = runBlocking {
        val a = fragment(reading()); val b = fragment(reading("La pista del aeropuerto.", article.copy(revision = 322)))
        for (f in listOf(a,b)) repo.attachArticle(f.articleKey!!)
        repo.check(a,false) { true }; val airport = repo.check(b,false) { true }.single()
        assertEquals("checked", airport.state)
        db.bookDao().insert(BookEntity(id = 42, title = "Book", author = "", fileName = "book", chapterCount = 1))
        val book = AiContext.build(42, reading().textChapter, 0, "book/chapter", engine, emptyMap(), "ANY")!!
        repo.check(book,false) { true }
        assertEquals(3, calls); assertEquals(2, repo.observe(42).first().size)
        assertTrue(WikiAiContext.applicable(reading(), engine, emptyMap(), appearance, listOf(airport), config.model).isEmpty())
        db.aiCheckDao().deleteArticle(a.articleKey!!)
        assertTrue(repo.observeArticle(a.articleKey!!).first().isEmpty())
        assertEquals(2, repo.observe(42).first().size)
        assertEquals(1, repo.observeArticle(b.articleKey!!).first().size)
    }
    @Test fun userMeaningWinsAndChangedContextOrModelInvalidatesTheResult() = runBlocking {
        val r = reading(); val f = fragment(r); repo.attachArticle(f.articleKey!!)
        val rows = repo.check(f,false) { true }
        val manual = mapOf("pista" to "мой перевод")
        val valid = WikiAiContext.applicable(r,engine,manual,appearance,rows,config.model)
        assertTrue(valid.isEmpty())
        val blocked = WikiAiContext.build(r,0,engine,manual,appearance,range = 12 until 17)
        assertNull(blocked)
        assertTrue(WikiAiContext.applicable(r,engine,emptyMap(),appearance,rows,"new-model").isEmpty())
        assertTrue(WikiAiContext.applicable(reading("Una pista en otro contexto."),engine,emptyMap(),appearance,rows,config.model).isEmpty())
    }
    @Test fun multiwordConstructionUndoAndCacheClearDoNotReapplyOrRebill() = runBlocking {
        val r = reading("La presa y el hombre se van identificando."); val f = fragment(r); repo.attachArticle(f.articleKey!!)
        val rows = repo.check(f,false) { true }; assertEquals(3, rows.size)
        val glosses = hashMapOf<String,String>(); val block = r.textChapter.blocks[0] as ReaderBlock.Text
        AiContext.overlay(glosses,block,0,rows,false)
        assertEquals("постепенно отождествляются", glosses["@0:${f.text.indexOf("se van")}"])
        repo.setState(rows.first().cacheKey,"rejected"); repo.clear(false)
        val cached = repo.check(f,true) { true }
        assertTrue(cached.all { it.state == "rejected" }); assertEquals(1, calls)
    }
    @Test fun deletionDuringARequestDoesNotResurrectTheArticleOrSavePaidResultsToAnotherOwner() = runBlocking {
        val f = fragment(reading()); repo.attachArticle(f.articleKey!!); gate = CompletableDeferred()
        val request = async { repo.check(f,false) { true } }
        while (calls == 0) delay(5)
        db.aiCheckDao().deleteArticle(f.articleKey!!); gate!!.complete(Unit)
        assertTrue(request.await().isEmpty()); assertTrue(db.aiCheckDao().all().isEmpty())
        assertTrue(repo.check(f,false) { true }.isEmpty()); assertEquals(1, calls)
    }
    @Test fun missingKeyMalformedResponseAndServerErrorKeepTheDictionaryAndCacheIntact() = runBlocking {
        val f = fragment(reading()); repo.attachArticle(f.articleKey!!)
        config = config.copy(hasKey = false); assertTrue(repo.check(f,false) { true }.isEmpty()); assertEquals(0, calls)
        config = config.copy(hasKey = true); malformed = true
        assertTrue(repo.check(f,false) { true }.isEmpty()); assertTrue(db.aiCheckDao().all().isEmpty())
        malformed = false; fail = true
        assertTrue(repo.check(f,false) { true }.isEmpty()); assertTrue(db.aiCheckDao().all().isEmpty())
        assertEquals("дорожка", engine.glossShort("pista")); assertEquals(2, calls)
    }
    @Test fun automaticCandidatePacketsAreBoundedAndRequireVisibleLocalHints() = runBlocking {
        val r = reading((1..100).joinToString(" ") { "pista" })
        assertNull(WikiAiContext.automatic(r,0,engine,emptyMap(),appearance,emptyMap()))
        val glosses = (r.textChapter.blocks[0] as ReaderBlock.Text).tokens.filter { it.text == "pista" }.associate { "@0:${it.start}" to "дорожка" }
        val f = WikiAiContext.automatic(r,0,engine,emptyMap(),appearance,glosses)!!
        assertEquals(20, f.terms.size)
        assertNull(WikiAiContext.automatic(reading("pista ".repeat(1500)),0,engine,emptyMap(),appearance,glosses))
    }
    @Test fun automaticModeSharesTheExistingSessionLimitAndCounters() = runBlocking {
        config = config.copy(limit = 1)
        val a = fragment(reading()); val b = fragment(reading(a = article.copy(id = 43)))
        repo.attachArticle(a.articleKey!!); repo.attachArticle(b.articleKey!!)
        repo.check(a,true) { true }; assertTrue(repo.check(b,true) { true }.isEmpty())
        assertEquals(AiUsage(1,50,0),repo.usage.value); assertEquals(1, calls)
    }
    @Test fun explicitUnknownWordGetsAContextualTranslationButIsNotAnAutomaticCandidate() = runBlocking {
        val r = reading("Desconocido.")
        assertNull(WikiAiContext.automatic(r, 0, engine, emptyMap(), appearance, emptyMap()))
        val f = WikiAiContext.build(r,0,engine,emptyMap(),appearance,range = 0 until 11)!!
        repo.attachArticle(f.articleKey!!)
        assertEquals("", f.terms.single().base)
        assertEquals("неизвестный", repo.check(f,false) { true }.single().translation)
    }
    @Test fun upgradeFromV17KeepsBookChecksAndAddsIndependentArticleOwnership() = runBlocking {
        db.close(); app.deleteDatabase(fileName)
        val old = SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(fileName), null)
        val schema = JSONObject(File("schemas/com.hanreader.app.LexiconDatabase/17.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i); val name = e.getString("tableName")
            old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = e.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        old.execSQL("INSERT INTO books (id,title,author,fileName,chapterCount,addedAt,lastOpenedAt,chapterIndex,blockIndex,charOffset,analyzedTokens) VALUES (42,'Book','','book',1,0,0,0,0,0,0)")
        old.execSQL("INSERT INTO ai_checks VALUES (?,42,0,0,?,0,5,0,5,'pista','дорожка','улика',NULL,'high','accepted',1)", arrayOf("a".repeat(64), "b".repeat(64)))
        old.version = 17; old.close()
        db = open(); repo = repository()
        assertEquals(18, db.openHelper.readableDatabase.version)
        val original = db.aiCheckDao().all().single()
        assertEquals(42L, original.bookId); assertNull(original.articleKey); assertEquals("accepted",original.state)
        val f = fragment(reading()); repo.attachArticle(f.articleKey!!); repo.check(f,false) { true }
        assertEquals(listOf(original), db.aiCheckDao().forBackup())
        db.bookDao().delete(42)
        assertEquals(2, repo.observeArticle(f.articleKey!!).first().size)
    }

    @Test fun aQueuedOffscreenCheckDoesNotSpendTokensOrPoisonFutureAttempts() = runBlocking {
        db.bookDao().insert(BookEntity(id = 7,title = "Book",author = "",fileName = "book",chapterCount = 1))
        val r = reading(); val f = fragment(r); repo.attachArticle(f.articleKey!!)
        val book = AiContext.build(7,r.textChapter,0,"book/chapter",engine,emptyMap(),"ANY")!!
        gate = CompletableDeferred()
        val running = async { repo.check(book,false) { true } }
        while (calls == 0) delay(5)
        var visible = true
        val queued = async { repo.checkVisible(f, canStart = { visible }) { true } }
        yield(); visible = false; gate!!.complete(Unit)
        running.await(); assertTrue(queued.await().isEmpty()); assertEquals(1,calls)
        visible = true
        assertTrue(repo.checkVisible(f, canStart = { visible }) { true }.isNotEmpty()); assertEquals(2,calls)
    }

    @Test fun acceptingAnUncertainProposalOnlyChangesItsArticle() = runBlocking {
        confidence = "medium"
        val a = fragment(reading()); val b = fragment(reading(a = article.copy(id = 43)))
        repo.attachArticle(a.articleKey!!); repo.attachArticle(b.articleKey!!)
        val first = repo.check(a,false) { true }; val second = repo.check(b,false) { true }
        assertEquals("proposed",first.single { it.translation != null }.state)
        val row = first.single { it.translation != null }; repo.setState(row.cacheKey,"accepted")
        assertEquals("accepted", repo.observeArticle(a.articleKey!!).first().single { it.translation != null }.state)
        assertEquals("proposed", repo.observeArticle(b.articleKey!!).first().single { it.translation != null }.state)
    }
    @Test fun unsupportedCharacterTransformationsFallBackWithoutARequest() = runBlocking {
        val r = reading(); val block = r.textChapter.blocks.single() as ReaderBlock.Text
        val bad = WikiReading(r.document, r.textChapter.copy(blocks = listOf(block.copy(shown = block.shown + "!"))), listOf(0), listOf(0))
        assertNull(WikiAiContext.build(bad,0,engine,emptyMap(),appearance,range = 12 until 17))
        assertEquals(0,calls)
    }

    @Test fun wholeParagraphSelectionWithPunctuationCannotBeBilledAsOneWord() = runBlocking {
        val r = reading("pista para investigar.")
        assertNull(WikiAiContext.build(r,0,engine,emptyMap(),appearance,range = 0 until r.document.blocks[0].text.length))
        assertNotNull(WikiAiContext.build(r,0,engine,emptyMap(),appearance,range = 0 until 5))
        assertEquals(0,calls)
    }

}
