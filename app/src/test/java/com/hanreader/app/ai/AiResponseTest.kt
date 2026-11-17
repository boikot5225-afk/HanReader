package com.hanreader.app.ai

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.BookEntity
import com.hanreader.app.LexiconDatabase
import com.hanreader.app.lang.*
import com.hanreader.app.reader.*
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Protocol variants, not a live model-quality test. All source sentences are fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiResponseTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: LexiconDatabase
    private val fileName = "ai-response-test.db"
    private var calls = 0
    private val config = AiConfig(enabled = true, automatic = true, consent = true, hasKey = true)
    private object MissingDictionary : LanguageModule {
        override val info = Languages.SPANISH
        override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText = { error("unused") }
        override fun glossShort(word: String, reading: String?): String? = null
        override fun cardTranslation(word: String, reading: String?): String? = null
    }
    private fun openDb() = Room.databaseBuilder(app, LexiconDatabase::class.java, fileName)
        .addMigrations(*LexiconDatabase.MIGRATIONS).allowMainThreadQueries().build()
    @Before fun setup() = runBlocking {
        app.deleteDatabase(fileName); db = openDb()
        db.bookDao().insert(BookEntity(id = 7, title = "Fixture", author = "", fileName = "fixture.epub", chapterCount = 1))
        Unit
    }
    @After fun cleanup() { db.close(); app.deleteDatabase(fileName) }
    private fun fragment(text: String = "Su apodo es Mataamigos.", target: String = "Mataamigos", start: Int = text.indexOf(target)): AiFragment {
        val tokens = Regex("\\p{L}+|\\s+|[^\\p{L}\\s]").findAll(text).map {
            RToken(it.value, "", if (it.value.first().isLetter()) TokenKind.HAN else TokenKind.PUNCT,
                it.range.first, it.range.last + 1, true)
        }.toList()
        val chapter = ChapterContent(0, "Fixture", listOf(ReaderBlock.Text(text, false, tokens)), 0, text.length)
        return AiContext.build(7, chapter, 0, "chapter.xhtml", MissingDictionary, emptyMap(), "ANY",
            range = start until start + target.length)!!
    }
    private fun result(f: AiFragment, translation: String? = "Убийца друзей"): JSONObject {
        val t = f.terms.single()
        return JSONObject().put("term_id", t.id).put("original", t.original).put("current_translation", t.base)
            .put("needs_correction", translation != null).put("suggested_translation", translation ?: JSONObject.NULL)
            .put("explanation", JSONObject.NULL).put("confidence", "high")
    }
    private fun packet(row: JSONObject) = JSONObject().put("results", JSONArray().put(row)).toString()
    private fun parse(f: AiFragment, row: JSONObject) = AiContext.validate(packet(row), f, config.model)
    private fun repository(reply: () -> String) = AiRepository(db, AiClient { _, _, _ ->
        calls++; AiReply(reply(), 12, true)
    }, { config }, { "mock-key-no-account" })

    @Test fun nicknameWithNullEchoEmptyExplanationAndNullSpanPersistsWithoutAnotherPaidRequest() = runBlocking {
        val f = fragment()
        val template = f.json().getJSONObject("response_template").getJSONArray("results").getJSONObject(0)
        assertEquals(f.terms.single().id, template.getString("term_id"))
        assertEquals("Mataamigos", template.getString("original"))
        assertEquals("", template.getString("current_translation"))
        val row = result(f).put("current_translation", JSONObject.NULL).put("explanation", "").put("span", JSONObject.NULL)
        var repo = repository { packet(row) }
        val saved = repo.check(f, false) { true }
        assertEquals("Убийца друзей", saved.single().translation)
        assertEquals("", saved.single().base); assertNull(saved.single().explanation)
        db.close(); db = openDb(); repo = repository { error("cache was lost") }
        assertEquals(saved, repo.check(f, false) { true }); assertEquals(1, calls)
    }

    @Test fun numericModelOffsetsDoNotMoveTheRequestedNicknameOrRequireCountingEmoji() {
        val f = fragment("😀 Él dijo: Mataamigos.")
        for (span in listOf(
            JSONObject().put("text", "Mataamigos").put("start", 0).put("end", 10),
            JSONObject().put("text", "Mataamigos"),
            JSONObject().put("text", "Mataamigos").put("start", JSONObject.NULL).put("end", JSONObject.NULL))) {
            val saved = parse(f, result(f).put("span", span)).single()
            assertEquals(f.terms.single().start, saved.start)
            assertEquals(f.terms.single().end, saved.end)
            assertEquals("Mataamigos", saved.original)
        }
    }

    @Test fun expressionUsesExactSourceTextAndStillProtectsManualValues() {
        val f = fragment("Ellos se van identificando.", "identificando")
        val r = result(f, "постепенно отождествляются")
            .put("span", JSONObject().put("text", "se van identificando"))
        val saved = parse(f, r).single()
        assertEquals(f.text.indexOf("se van"), saved.start)
        assertEquals("se van identificando", saved.original)
        val protected = f.copy(protected = listOf(saved.start until saved.start + 2))
        val e = assertThrows(AiResponseFailure::class.java) { parse(protected, r) }
        assertEquals(AiResponseProblem.PROTECTED, e.problem)
    }

    @Test fun repeatedOverlappingPhrasesAreRejectedUnlessExactOldOffsetsDisambiguateThem() {
        val f = fragment("no no no", "no", 3)
        val r = result(f, "нет нет").put("span", JSONObject().put("text", "no no"))
        val e = assertThrows(AiResponseFailure::class.java) { parse(f, r) }
        assertEquals(AiResponseProblem.SPAN, e.problem)
        r.getJSONObject("span").put("start", 3).put("end", 8)
        val saved = parse(f, r).single()
        assertEquals(3, saved.start); assertEquals(8, saved.end)
    }

    @Test fun emptyUnusedSuggestionIsNotAnEmptyTranslationAndKnownBaseCannotBecomeNull() {
        val f = fragment()
        val checked = result(f, null).put("suggested_translation", "").put("explanation", "   ")
        assertNull(parse(f, checked).single().translation)
        assertEquals("checked", parse(f, checked).single().state)
        val blankCorrection = result(f).put("suggested_translation", " ")
        assertEquals(AiResponseProblem.VALUE,
            assertThrows(AiResponseFailure::class.java) { parse(f, blankCorrection) }.problem)
        val known = f.copy(terms = f.terms.map { it.copy(base = "старое значение") })
        assertFalse(known.json().has("response_template"))
        assertEquals(AiResponseProblem.SOURCE,
            assertThrows(AiResponseFailure::class.java) { parse(known, result(known).put("current_translation", JSONObject.NULL)) }.problem)
    }

    @Test fun formattingWhitespaceIsNormalizedButHiddenControlCharactersAreRejected() {
        val f = fragment()
        val r = result(f).put("suggested_translation", "Убийца\nдрузей")
            .put("explanation", "Прозвище\tпо контексту.\n")
        val saved = parse(f, r).single()
        assertEquals("Убийца друзей", saved.translation)
        assertEquals("Прозвище по контексту.", saved.explanation)
        r.put("suggested_translation", "\n\t")
        assertEquals(AiResponseProblem.VALUE,
            assertThrows(AiResponseFailure::class.java) { parse(f, r) }.problem)
        r.put("suggested_translation", "Убийца\u0000друзей")
        assertEquals(AiResponseProblem.VALUE,
            assertThrows(AiResponseFailure::class.java) { parse(f, r) }.problem)
    }

    @Test fun mismatchedIdentityInvalidTranslationsAndFabricatedSpansNeverPersistOrLeakInDiagnostics() = runBlocking {
        val f = fragment()
        val bad = listOf(
            packet(result(f).put("original", "другое слово")),
            packet(result(f).put("term_id", "other-id")),
            packet(result(f).put("current_translation", "нет в словаре")),
            packet(result(f).put("suggested_translation", "")),
            packet(result(f).put("confidence", "certain")),
            packet(result(f).put("explanation", JSONArray())),
            packet(result(f).put("span", JSONObject().put("text", "Mataamigo"))), // partial token
            packet(result(f).put("span", JSONObject().put("text", "Mataamigos").put("start", "0"))),
            "{\"error\":\"mock-key-no-account private-response-body\"}"
        )
        var response = bad.first()
        val repo = repository { response }
        for (body in bad) {
            response = body
            repo.check(f, false) { true }
            assertTrue(repo.observe(7).first().isEmpty())
            val message = repo.message.value!!
            assertTrue(message.contains("Переводы не сохранены"))
            assertFalse(message.contains("private-response-body")); assertFalse(message.contains("mock-key"))
        }
        assertEquals(bad.size, calls)
        assertTrue(repo.message.value!!.contains("[FORMAT]"))
    }
}
