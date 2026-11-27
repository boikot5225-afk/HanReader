package com.hanreader.app.ai

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.*
import com.hanreader.app.reader.*
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
class ContextAiTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: LexiconDatabase
    private val fileName = "context-ai-test.db"
    private var calls = 0
    private var gate: CompletableDeferred<Unit>? = null
    private var config = AiConfig(enabled = true, automatic = true, consent = true, hasKey = true)
    private var reply: (JSONObject) -> AiReply = { fakeReply(it) }
    private lateinit var repo: AiRepository
    private val client = AiClient { _, _, payload -> calls++; gate?.await(); reply(payload) }
    private object Dictionary : LanguageModule {
        override val info = Languages.SPANISH
        override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText = { error("not needed") }
        override fun glossShort(word: String, reading: String?): String? = mapOf("pista" to "дорожка",
            "periódicos" to "газеты", "abusado" to "сообразительный", "se" to "себя", "van" to "идут",
            "identificando" to "отождествляя", "hermana" to "сестра")[word.lowercase()]
        override fun cardTranslation(word: String, reading: String?) = glossShort(word, reading)
    }
    private fun chapter(text: String): ChapterContent {
        val tokens = Regex("\\p{L}+|\\s+|[^\\p{L}\\s]").findAll(text).map {
            RToken(it.value, "", if (it.value.first().isLetter()) TokenKind.HAN else TokenKind.PUNCT,
                it.range.first, it.range.last + 1, true)
        }.toList()
        return ChapterContent(0, "Test", listOf(ReaderBlock.Text(text, false, tokens)), 0, text.length)
    }
    private fun fragment(text: String = "Buscaba una pista en los periódicos.", manual: Map<String, String> = emptyMap(), variant: String = "ANY") =
        AiContext.build(7, chapter(text), 0, "OEBPS/chapter.xhtml", Dictionary, manual, variant)!!

    private fun openDb() = Room.databaseBuilder(app, LexiconDatabase::class.java, fileName)
        .addMigrations(*LexiconDatabase.MIGRATIONS).allowMainThreadQueries().build()
    private fun repository() = AiRepository(db, client, { config }, { if (config.hasKey) "mock-key-no-account" else null })
    @Before fun setup() = runBlocking {
        app.deleteDatabase(fileName); db = openDb()
        db.bookDao().insert(BookEntity(id = 7, title = "Test", author = "", fileName = "test.epub", chapterCount = 1))
        repo = repository()
    }
    @After fun cleanup() { db.close(); app.deleteDatabase(fileName) }

    private fun fakeReply(payload: JSONObject): AiReply {
        val text = payload.getString("paragraph")
        val terms = payload.getJSONArray("terms")
        val results = JSONArray()
        for (i in 0 until terms.length()) {
            val term = terms.getJSONObject(i)
            val original = term.getString("original")
            var corrected: String? = when (original.lowercase()) {
                "pista" -> if ("periódicos" in text) "улика" else null
                "abusado" -> "осторожнее"
                "se", "van", "identificando" -> "постепенно отождествляются"
                else -> null
            }
            val r = JSONObject().put("term_id", term.getString("term_id")).put("original", original)
                .put("current_translation", term.getString("current_translation"))
                .put("needs_correction", corrected != null).put("suggested_translation", corrected ?: JSONObject.NULL)
                .put("explanation", if (original == "hermana") "Недостаточно контекста." else JSONObject.NULL)
                .put("confidence", if (original == "hermana") "low" else "high")
            if (original in setOf("se", "van", "identificando")) {
                val start = text.indexOf("se van identificando")
                r.put("span", JSONObject().put("start", start).put("end", start + "se van identificando".length)
                    .put("text", "se van identificando"))
            }
            results.put(r)
        }
        return AiReply(JSONObject().put("results", results).toString(), 50, true)
    }

    @Test fun detectiveMeaningChangesButNewspapersStayAndAnotherContextIsIndependent() = runBlocking {
        val f = fragment()
        val rows = repo.check(f, false) { true }
        assertEquals("улика", rows.single { it.original == "pista" }.translation)
        assertEquals("accepted", rows.single { it.original == "pista" }.state)
        assertNull(rows.single { it.original == "periódicos" }.translation)
        val airport = repo.check(fragment("La pista del aeropuerto."), false) { true }.single()
        assertEquals("checked", airport.state); assertNull(airport.translation)
        assertEquals(2, calls)
    }

    @Test fun constructionIsOneHintAndUndoRejectsAllDuplicateHeads() = runBlocking {
        val f = fragment("La presa y el hombre se van identificando.")
        val rows = repo.check(f, false) { true }
        assertEquals(3, rows.size)
        val glosses = hashMapOf<String, String>()
        val b = chapter(f.text).blocks[0] as ReaderBlock.Text
        AiContext.overlay(glosses, b, 0, rows, false)
        val start = f.text.indexOf("se van")
        assertEquals("постепенно отождествляются", glosses["@0:$start"])
        assertNull(glossOf(glosses, b.tokens.first { it.text == "van" }, 0))
        repo.setState(rows.first().cacheKey, "rejected")
        assertTrue(repo.observe(7).first().all { it.state == "rejected" })
        repo.check(f, true) { true }
        assertEquals(1, calls)
    }

    @Test fun regionalAndUncertainResultsKeepTheirScope() = runBlocking {
        val mexico = fragment("Abusado, güey, que me los pisa.", variant = "LATAM")
        assertEquals("осторожнее", repo.check(mexico, false) { true }.single().translation)
        assertNotEquals(mexico.contextHash, mexico.copy(variant = "SPAIN").contextHash)
        val uncertain = repo.check(fragment("Una hermana."), false) { true }.single()
        assertEquals("checked", uncertain.state); assertNull(uncertain.translation)
        assertNotNull(uncertain.explanation)
    }

    @Test fun allManualScopesWinAndInvalidateExistingAi() {
        val original = fragment()
        val row = AiContext.validate(fakeReply(original.json()).content, original, config.model)
        for (key in listOf("pista", ContextMeanings.inBook(7, "pista"), ContextMeanings.here(7, 0, 0, original.text.indexOf("pista")))) {
            val manual = mapOf(key to "ручное значение")
            val f = fragment(manual = manual)
            assertFalse(f.terms.any { it.original == "pista" })
            assertTrue(AiContext.applicable(f, row, config.model).isEmpty())
            val t = (chapter(f.text).blocks[0] as ReaderBlock.Text).tokens.first { it.text == "pista" }
            assertEquals("ручное значение", AiContext.manual(7, 0, 0, t, Dictionary, manual))
        }
    }

    @Test fun repeatedOpeningAndRepositoryRestartReuseDiskWithoutTokens() = runBlocking {
        val f = fragment()
        repo.check(f, false) { true }
        repo.check(f, true) { true }
        db.close(); db = openDb(); repo = repository()
        val saved = repo.check(f, false) { true }
        assertEquals(1, calls); assertEquals(2, saved.size)
        assertEquals(0, repo.usage.value.requests)
        assertEquals("улика", saved.single { it.original == "pista" }.translation)
    }

    @Test fun archiveRestoresCorrectionsAndPaidChecksOnFreshInstallation() = runBlocking {
        val f = fragment()
        val rows = repo.check(f, false) { true }
        val book = db.bookDao().all().single()
        val source = File(app.cacheDir, "ai-backup-source").apply { mkdirs() }
        File(source, book.fileName).writeText("test book bytes")
        val out = java.io.ByteArrayOutputStream()
        BackupArchive.write(out, Backup(42, emptyList(), emptyList(), emptyList(),
            library = LibraryBackup(listOf(book), emptyList(), emptyList(), emptyList(),
                languages = mapOf(book.id to "es-ES")), aiChecks = db.aiCheckDao().all()), source)
        val backup = BackupArchive.read(out.toByteArray().inputStream(), File(app.cacheDir, "ai-backup-restore"))
        assertEquals(rows.associateBy { it.cacheKey }, backup.aiChecks!!.associateBy { it.cacheKey })
        assertEquals("test book bytes", backup.bookFiles.getValue(book.fileName).readText())
        db.close(); app.deleteDatabase(fileName); db = openDb()
        db.bookDao().insertAllStrict(backup.library!!.books)
        db.aiCheckDao().restore(backup.aiChecks!!)
        db.close(); db = openDb(); repo = repository()
        val saved = repo.check(fragment(), false) { true }
        assertEquals(rows, saved)
        assertEquals(1, calls)
        assertEquals("улика", saved.single { it.original == "pista" }.translation)
        assertNull(saved.single { it.original == "periódicos" }.translation)
        source.deleteRecursively(); backup.bookFiles.values.forEach { it.delete() }
    }

    @Test fun fontAndPageAreNotPartOfIdentityButTextModelRulesAndTranslationAre() {
        val f = fragment()
        assertEquals(f.key(f.terms.first(), config.model), fragment().key(fragment().terms.first(), config.model))
        assertNotEquals(f.key(f.terms.first(), config.model), f.key(f.terms.first(), "other-model"))
        assertNotEquals(f.key(f.terms.first(), config.model), f.key(f.terms.first().copy(base = "улика"), config.model))
        assertNotEquals(f.contextHash, f.copy(text = f.text + " Más.").contextHash)
        assertNotEquals(f.contextHash, f.copy(resource = "other.xhtml").contextHash)
        assertEquals(f.contextHash, fragment(manual = mapOf(ContextMeanings.here(7, 9, 10, 0) to "другой абзац")).contextHash)
        assertEquals(f.contextHash, fragment(manual = mapOf("palabra_ausente" to "не встречается здесь")).contextHash)
    }

    @Test fun withoutKeyOrWhenDisabledDictionaryWorksWithoutNetwork() = runBlocking {
        config = config.copy(hasKey = false)
        assertTrue(repo.check(fragment(), false) { true }.isEmpty())
        config = config.copy(hasKey = true, enabled = false)
        assertTrue(repo.check(fragment(), true) { true }.isEmpty())
        assertEquals("дорожка", Dictionary.glossShort("pista")); assertEquals(0, calls)
    }

    @Test fun serverFailureDoesNotWriteOrAutomaticallyRetryAndCountsUnknownUsage() = runBlocking {
        reply = { throw AiFailure("Сервер недоступен") }
        repo.check(fragment(), true) { true }
        repo.check(fragment(), true) { true }
        assertTrue(repo.observe(7).first().isEmpty()); assertEquals(1, calls)
        assertEquals(1, repo.usage.value.unknown)
        assertEquals("дорожка", Dictionary.glossShort("pista"))
    }

    @Test fun truncatedMalformedAndUnknownTermResponsesNeverPersist() = runBlocking {
        val f = fragment()
        for (bad in listOf(AiReply("{", 10, true), AiReply(fakeReply(f.json()).content, 10, false),
            AiReply("{\"results\":[]}", 10, true))) {
            reply = { bad }; repo.check(f, false) { true }
            assertTrue(repo.observe(7).first().isEmpty())
        }
        assertEquals(30, repo.usage.value.tokens)
    }

    @Test fun wrongAnswerForOneWordKeepsTheOthers() = runBlocking {
        val f = fragment()
        reply = { AiReply(fakeReply(it).content.replace("t_12_17", "alien"), 10, true) }
        val rows = repo.check(f, false) { true }
        assertEquals(listOf("periódicos"), rows.map { it.original })
        assertEquals(listOf("periódicos"), repo.observe(7).first().map { it.original })
        assertTrue(repo.message.value!!.startsWith("Часть ответа DeepSeek отклонена"))
        val pista = f.terms.single { it.original == "pista" }
        assertTrue(repo.wordFeedback.value.failures.keys.any { it.start == pista.start })
    }

    @Test fun visibleParagraphsShareOneRequestWithoutRepeatingTheirContext() = runBlocking {
        val texts = listOf("Buscaba una pista en los periódicos.", "La pista del aeropuerto.")
        var offset = 0
        val blocks = texts.map { text ->
            val tokens = Regex("\\p{L}+|\\s+|[^\\p{L}\\s]").findAll(text).map {
                RToken(it.value, "", if (it.value.first().isLetter()) TokenKind.HAN else TokenKind.PUNCT,
                    it.range.first, it.range.last + 1, true)
            }.toList()
            ReaderBlock.Text(text, false, tokens).also { offset += text.length }
        }
        val chapter = ChapterContent(0, "Test", blocks, 0, offset)
        val fragments = (0..1).map { AiContext.build(7, chapter, it, "OEBPS/chapter.xhtml", Dictionary, emptyMap(), "ANY")!! }
        var sent: JSONObject? = null
        reply = { payload ->
            sent = payload
            val results = JSONArray()
            val paragraphs = payload.getJSONArray("paragraphs")
            for (i in 0 until paragraphs.length()) {
                val rows = JSONObject(fakeReply(paragraphs.getJSONObject(i)).content).getJSONArray("results")
                for (j in 0 until rows.length()) results.put(rows.get(j))
            }
            AiReply(JSONObject().put("results", results).toString(), 50, true)
        }
        repo.checkVisibleGroup(fragments.map { AiVisibleCheck(it, { true }) { true } })
        assertEquals(1, calls)
        val paragraphs = sent!!.getJSONArray("paragraphs")
        assertFalse(paragraphs.getJSONObject(0).has("next_context"))
        assertFalse(paragraphs.getJSONObject(1).has("previous_context"))
        val saved = repo.observe(7).first()
        assertEquals("улика", saved.single { it.original == "pista" && it.block == 0 }.translation)
        assertEquals("checked", saved.single { it.original == "pista" && it.block == 1 }.state)
        assertEquals(3, saved.size)
    }

    @Test fun dailyLimitSurvivesRestartAndResetsAtMidnight() = runBlocking {
        val store = object : AiUsageStore {
            var value = AiUsage()
            override fun load() = value
            override fun save(usage: AiUsage) { value = usage }
        }
        var day = 100L
        config = config.copy(limit = 1)
        fun daily() = AiRepository(db, client, { config }, { "mock-key-no-account" }, store) { day }
        daily().check(fragment(), false) { true }
        repo = daily()
        assertEquals(1, repo.usage.value.requests)
        repo.check(fragment("La pista del aeropuerto."), false) { true }
        assertEquals(1, calls); assertTrue(repo.message.value!!.contains("дневной лимит"))
        day = 101
        repo.check(fragment("La pista del aeropuerto."), false) { true }
        assertEquals(2, calls); assertEquals(1, repo.usage.value.requests)
    }

    @Test fun sentenceIsSentOnlyForRepeatedWords() {
        fun term(f: AiFragment, word: String) = f.json().getJSONArray("terms").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("original") == word }
        }
        assertFalse(term(fragment(), "pista").has("sentence"))
        assertTrue(term(fragment("Una pista. Otra pista."), "pista").has("sentence"))
    }

    @Test fun invalidSpansWrongTypesDuplicateIdsAndTrailingGarbageAreRejected() {
        val f = fragment()
        fun rejects(content: String) {
            var rejected = false
            try { AiContext.validate(content, f, config.model) } catch (_: Exception) { rejected = true }
            assertTrue("accepted malformed response", rejected)
        }
        val good = fakeReply(f.json()).content
        rejects(good + " trailing")
        rejects(good.replace("\"results\"", "'results'"))
        val first = f.terms.single { it.id == JSONObject(good).getJSONArray("results").getJSONObject(0).getString("term_id") }
        // A bad row loses only its own word.
        fun rejectsFirstOnly(content: String) {
            val parsed = AiContext.read(content, f, config.model)
            assertTrue(parsed.problems.isNotEmpty())
            assertTrue(parsed.rows.none { it.termStart == first.start })
            assertEquals(1, parsed.rows.size)
        }
        val root = JSONObject(good)
        root.getJSONArray("results").getJSONObject(0).put("needs_correction", "true")
        rejectsFirstOnly(root.toString())
        val span = JSONObject(good)
        span.getJSONArray("results").getJSONObject(0).put("span", JSONObject().put("start", 0).put("end", 99999).put("text", "bad"))
        rejectsFirstOnly(span.toString())
        val dup = JSONObject(good)
        dup.getJSONArray("results").put(1, dup.getJSONArray("results").getJSONObject(0))
        assertTrue(AiContext.read(dup.toString(), f, config.model).problems.contains(AiResponseProblem.TERMS))
    }

    @Test fun mediumConfidenceIsOnlyProposedAndCanBeExplicitlyAccepted() = runBlocking {
        reply = { fakeReply(it).let { r -> r.copy(content = r.content.replace("\"high\"", "\"medium\"")) } }
        val rows = repo.check(fragment(), false) { true }
        val correction = rows.single { it.translation != null }
        assertEquals("proposed", correction.state)
        repo.setState(correction.cacheKey, "accepted")
        assertEquals("accepted", repo.observe(7).first().first { it.translation != null }.state)
    }

    @Test fun concurrentChecksDeduplicateAndDailyLimitIncludesAllBatches() = runBlocking {
        coroutineScope { List(4) { async(Dispatchers.Default) { repo.check(fragment(), false) { true } } }.awaitAll() }
        assertEquals(1, calls)
        assertEquals(50, repo.usage.value.tokens)
        config = config.copy(limit = 1)
        repo.check(fragment("La pista del aeropuerto."), false) { true }
        assertEquals(1, calls); assertTrue(repo.message.value!!.contains("лимит"))
    }

    @Test fun connectionErrorBelongsOnlyToWordsInTheFailedCheck() = runBlocking {
        val f = fragment()
        val term = f.terms.first()
        val target = f.copy(terms = listOf(term))
        val selected = AiWordLocation.book(f.book, f.chapter, f.block, term.start, term.end, config.model)
        reply = { throw AiFailure("Нет связи с DeepSeek.") }
        assertTrue(repo.checkVisible(target, canStart = { true }) { true }.isEmpty())
        val feedback = repo.wordFeedback.value
        assertFalse(feedback.isChecking(selected))
        assertEquals("Нет связи с DeepSeek.", feedback.error(selected))
        assertNull(feedback.error(selected.copy(block = selected.block + 1)))
        assertNull(feedback.error(selected.copy(start = term.end, end = term.end + 1)))
        assertNull(feedback.error(selected.copy(model = "different")))
        reply = ::fakeReply
        assertTrue(repo.check(target, automatic = false) { true }.isNotEmpty())
        assertNull(repo.wordFeedback.value.error(selected))
    }

    @Test fun bookDeletionAndContextChangeWhileWaitingPreventSaving() = runBlocking {
        var valid = true
        reply = { payload -> valid = false; fakeReply(payload) }
        repo.check(fragment(), false) { valid }
        assertTrue(repo.observe(7).first().isEmpty())
        reply = { payload -> fakeReply(payload) }
        db.bookDao().delete(7)
        repo.check(fragment(), false) { true }
        assertTrue(repo.observe(7).first().isEmpty())
    }

    @Test fun cacheClearPreservesAcceptedCorrectionsAndRejectedAnswers() = runBlocking {
        val rows = repo.check(fragment(), false) { true }
        repo.clear(false)
        assertEquals(1, repo.observe(7).first().size)
        repo.setState(rows.single { it.translation != null }.cacheKey, "rejected")
        repo.clear(false); assertEquals("rejected", repo.observe(7).first().single().state)
        repo.clear(true); assertTrue(repo.observe(7).first().isEmpty())
    }

    @Test fun cancellationDoesNotSaveOrAutomaticallyResendUncertainPaidWork() = runBlocking {
        gate = CompletableDeferred()
        val job = launch { repo.check(fragment(), true) { true } }
        withTimeout(3000) { while (calls == 0) delay(1) }
        job.cancelAndJoin(); gate = null
        assertTrue(repo.observe(7).first().isEmpty())
        repo.check(fragment(), true) { true }
        assertEquals(1, calls); assertEquals(1, repo.usage.value.unknown)
    }

    @Test fun longParagraphIsBatchedInsteadOfOneRequestPerWord() = runBlocking {
        val f = fragment((1..45).joinToString(" ") { "pista" } + ".")
        assertEquals(45, repo.check(f, true) { true }.size)
        assertEquals(3, calls)
        repo.check(f, true) { true }; assertEquals(3, calls)
    }

    @Test fun constructionCannotCoverAManuallyCorrectedNeighbour() = runBlocking {
        val f = fragment("La presa y el hombre se van identificando.", manual = mapOf("van" to "ручное"))
        assertFalse(f.terms.any { it.original == "van" })
        repo.check(f, false) { true }
        assertTrue(repo.observe(7).first().isEmpty())
    }

    @Test fun explicitlyAcceptingAConflictingSuggestionReplacesOnlyTheChosenSpan() = runBlocking {
        val f = fragment("La presa y el hombre se van identificando.")
        repo.check(f.copy(terms = f.terms.take(1)), false) { true }
        reply = { fakeReply(it).let { r -> r.copy(content = r.content.replace("постепенно отождествляются", "становятся похожими")) } }
        val second = repo.check(f.copy(terms = f.terms.drop(1)), false) { true }
        assertTrue(second.all { it.state == "proposed" })
        repo.setState(second.first().cacheKey, "accepted")
        val accepted = repo.observe(7).first().filter { it.state == "accepted" }
        assertTrue(accepted.isNotEmpty())
        assertTrue(accepted.all { it.translation == "становятся похожими" })
    }

    @Test fun upgradingV14PreservesBooksAndManualMeanings() = runBlocking {
        db.close(); app.deleteDatabase(fileName)
        val file = app.getDatabasePath(fileName)
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        val schema = JSONObject(File("schemas/com.hanreader.app.LexiconDatabase/14.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i); val name = e.getString("tableName")
            old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = e.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        old.execSQL("INSERT INTO books (id,title,author,fileName,chapterCount,addedAt,lastOpenedAt,chapterIndex,blockIndex,charOffset,analyzedTokens) VALUES (7,'Book','','x.epub',1,0,0,0,0,0,0)")
        old.execSQL("INSERT INTO user_meanings (language,word,meaning,updatedAt) VALUES ('es-ES','pista','ручное',1)")
        old.version = 14; old.close()
        db = openDb()
        assertEquals("Book", db.bookDao().get(7)!!.title)
        assertEquals("ручное", db.userMeaningDao().all().single().meaning)
        assertTrue(db.aiCheckDao().observe(7).first().isEmpty())
    }
}
