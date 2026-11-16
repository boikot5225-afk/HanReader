package com.hanreader.app.ai

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.en.EnEngineTest
import com.hanreader.app.lang.*
import com.hanreader.app.reader.*
import com.hanreader.app.zh.*
import kotlinx.coroutines.runBlocking
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
class AiLanguagesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: LexiconDatabase
    private val en get() = EnEngineTest.engine
    private val zh by lazy { ZhEngine(ZhSegmenterTest.dict, 0, russian = mapOf("银行" to "банк", "门口" to "вход", "回来" to "возвращаться"),
        script = ZhScript.parse(File("src/main/assets/zh/script.tsv").inputStream())) }
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(app, LexiconDatabase::class.java).allowMainThreadQueries().build()
        db.bookDao().insert(BookEntity(id = 1, title = "Fixture", author = "", fileName = "test.epub", chapterCount = 1))
        Unit
    }
    @After fun cleanup() { db.close() }
    private fun chapter(text: String, engine: LanguageModule): ChapterContent {
        val p = engine.chapter(listOf(text), TextOptions())(text)
        return ChapterContent(0, "Test", listOf(ReaderBlock.Text(p.text, false, p.tokens, p.shown)), 0, p.text.length)
    }
    private fun fragment(text: String, engine: LanguageModule, variant: String = "ANY", manual: Map<String, String> = emptyMap()) =
        AiContext.build(1, chapter(text, engine), 0, "chapter.xhtml", engine, manual, variant)!!
    private fun response(f: AiFragment, target: String, translation: String, span: String? = null): String {
        val results = JSONArray()
        for (t in f.terms) {
            val correct = t.original == target
            val r = JSONObject().put("term_id", t.id).put("original", t.original).put("current_translation", t.base)
                .put("needs_correction", correct).put("suggested_translation", if (correct) translation else JSONObject.NULL)
                .put("explanation", JSONObject.NULL).put("confidence", "high")
            if (correct && span != null) {
                val start = f.text.indexOf(span)
                r.put("span", JSONObject().put("start", start).put("end", start + span.length).put("text", span))
            }
            results.put(r)
        }
        return JSONObject().put("results", results).toString()
    }

    @Test fun englishUsesItsLanguageLemmasAndContextAndReusesSavedChecks() = runBlocking {
        val f = fragment("She went to the bank by the river.", en, "SPAIN")
        assertEquals("en-US", f.json().getString("language"))
        assertEquals("ru", f.json().getString("target_language"))
        assertEquals("go", f.terms.first { it.original == "went" }.lemma)
        assertEquals("ANY", f.variant)
        assertEquals(f.contextHash, fragment(f.text, en, "LATAM").contextHash)
        var calls = 0
        val repo = AiRepository(db, AiClient { _, _, payload ->
            calls++; assertEquals("en-US", payload.getString("language"))
            AiReply(response(f, "bank", "берег"), 40, true)
        }, { AiConfig(enabled = true, automatic = true, consent = true, hasKey = true) }, { "mock-key" })
        val rows = repo.check(f, true) { true }
        assertEquals("берег", rows.first { it.original == "bank" }.translation)
        repo.check(f, false) { true }; assertEquals(1, calls)
        val finance = fragment("She went to the bank to get money.", en)
        assertNotEquals(f.contextHash, finance.contextHash)
    }

    @Test fun chinesePreservesTraditionalSourceAndPinyinAndDoesNotMistakeReadingsForPhrases() {
        val text = "他站在銀行門口。"
        val f = fragment(text, zh, "LATAM")
        assertEquals("zh-CN", f.language); assertEquals(text, f.json().getString("paragraph"))
        assertEquals("ANY", f.variant)
        val bank = f.terms.first { it.lemma == "银行" }
        assertEquals("銀行", bank.original)
        assertTrue(bank.reading!!.contains("háng"))
        assertEquals(2, bank.end - bank.start)
        val rows = AiContext.validate(response(f, "銀行", "банк"), f, "deepseek-flash")
        assertEquals(rows, AiContext.applicable(f, rows, "deepseek-flash"))
        val b = chapter(text, zh).blocks[0] as ReaderBlock.Text
        val originalTokens = b.tokens.toList()
        val glosses = hashMapOf<String, String>()
        AiContext.overlay(glosses, b, 0, rows, false)
        assertEquals("банк", glossOf(glosses, b.tokens.first { it.text == "银行" }, 0))
        assertEquals(originalTokens, b.tokens); assertEquals(text, b.shown)
    }

    @Test fun chineseConstructionWithoutSpacesGetsOneHintAndCannotOverwriteManualPhrase() {
        val f = fragment("他终于回来了。", zh)
        val rows = AiContext.validate(response(f, "回来", "вернулся", "回来了"), f, "deepseek-flash")
        val b = chapter(f.text, zh).blocks[0] as ReaderBlock.Text
        val glosses = hashMapOf<String, String>()
        AiContext.overlay(glosses, b, 0, rows, false)
        assertEquals("вернулся", glossOf(glosses, b.tokens.first { it.text == "回来" }, 0))
        assertNull(glossOf(glosses, b.tokens.first { it.text == "了" }, 0))
        val manual = fragment(f.text, zh, manual = mapOf("回来了" to "ручное выражение"))
        assertFalse(manual.terms.any { it.original in setOf("回来", "了") })
        assertTrue(AiContext.applicable(manual, rows, "deepseek-flash").isEmpty())
    }

    @Test fun englishSeparatedPhrasalVerbIsValidatedAsOneContextualUnit() {
        val f = fragment("She turned it off.", en)
        // The English module now recognises the separated expression before the AI check.
        assertEquals("turn off", f.terms.first { it.original == "turned" }.lemma)
        val rows = AiContext.validate(response(f, "turned", "выключила", "turned it off"), f, "model")
        val b = chapter(f.text, en).blocks[0] as ReaderBlock.Text
        val glosses = hashMapOf<String, String>()
        AiContext.overlay(glosses, b, 0, rows, false)
        assertEquals("выключила", glossOf(glosses, b.tokens.first { it.text == "turned" }, 0))
        assertNull(glossOf(glosses, b.tokens.first { it.text == "off" }, 0))
    }

    @Test fun manuallySelectedChineseRangeKeepsItsReadingAndReappliesAfterReopening() {
        val text = "他终于回来了。"
        val ch = chapter(text, zh)
        val full = fragment(text, zh)
        val start = text.indexOf("回来")
        val end = text.indexOf("了") + 1
        val selected = AiContext.build(1, ch, 0, "chapter.xhtml", zh, emptyMap(), "ANY", range = start until end)!!
        assertEquals("回来了", selected.terms.single().original)
        assertTrue(selected.terms.single().reading!!.contains("huí"))
        val rows = AiContext.validate(response(selected, "回来了", "вернулся"), selected, "model")
        assertEquals(rows, AiContext.applicable(full, rows, "model"))
    }

    @Test fun languageAndChineseReadingArePartOfCacheIdentityButSpanishLegacyKeysStayValid() {
        val f = fragment("She saw a bank.", en)
        assertNotEquals(f.contextHash, f.copy(language = "zh-CN").contextHash)
        val chinese = fragment("銀行", zh)
        val t = chinese.terms.single()
        assertNotEquals(chinese.key(t, "model"), chinese.key(t.copy(reading = "wrong"), "model"))
        val spanish = f.copy(language = "es-ES")
        val oldHash = AiContext.hash(JSONArray(listOf(spanish.book, spanish.resource, spanish.chapter, spanish.block,
            spanish.text, spanish.previous, spanish.next, spanish.variant, spanish.manualHash, "es-context-1")).toString())
        assertEquals(oldHash, spanish.contextHash)
        val term = spanish.terms.first()
        assertEquals(AiContext.hash(JSONArray(listOf(oldHash, "model", term.start, term.end,
            term.original, term.lemma, term.base)).toString()), spanish.key(term, "model"))
    }

    @Test fun manualValuesProtectBothEnglishAndChineseAndChineseTokenCannotBeSplit() {
        for ((engine, text, key) in listOf(Triple(en, "bank", "bank"), Triple(zh, "銀行", "银行"))) {
            val ch = chapter(text, engine)
            assertNull(AiContext.build(1, ch, 0, "chapter.xhtml", engine, mapOf(key to "ручное"), "ANY"))
        }
        val f = fragment("銀行", zh)
        val root = JSONObject(response(f, "銀行", "банк"))
        root.getJSONArray("results").getJSONObject(0).put("span", JSONObject().put("start", 0).put("end", 1).put("text", "銀"))
        try { AiContext.validate(root.toString(), f, "model"); fail("split a Chinese token") } catch (_: IllegalArgumentException) {}
    }
}
