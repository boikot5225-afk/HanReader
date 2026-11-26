package com.hanreader.app.reader

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.ai.*
import com.hanreader.app.lang.*
import com.hanreader.app.wiki.*
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WikipediaAiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previousLanguage = Languages.current
    private val previousAppearance = AppearanceStore.appearance.value
    private val article = WikiArticle("es",42,"Una investigación","Introducción.",321,1234,false)
    private val source = object : WikipediaSource {
        override suspend fun search(language: String, query: String) = emptyList<WikiHit>()
        override suspend fun article(language: String, id: Long) = article
        override suspend fun translation(article: WikiArticle, language: String): WikiArticle? = null
    }
    private val dictionary = object : LanguageModule {
        override val info = Languages.SPANISH
        override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText = { text ->
            PreparedText(text,text,Regex("\\p{L}+|\\s+|[^\\p{L}\\s]").findAll(text).map {
                RToken(it.value,"",if(it.value.any(Char::isLetter)) TokenKind.HAN else if(it.value.isBlank()) TokenKind.SPACE else TokenKind.PUNCT,
                    it.range.first,it.range.last+1,true)
            }.toList())
        }
        override fun glossShort(word: String, reading: String?) = if(word == "pista") "дорожка" else null
        override fun cardTranslation(word: String, reading: String?) = glossShort(word, reading)
    }
    private val calls = AtomicInteger()
    private val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var gate: CompletableDeferred<Unit>? = null
    private lateinit var ai: ContextAi
    private lateinit var vault: AiKeyVault
    private fun makeAi(automatic: Boolean): ContextAi {
        vault = AiKeyVault(app) { SecretKeySpec(ByteArray(16) { 7 }, "AES") }
        vault.save("mock-key-not-a-real-account")
        val settings = AiSettings(app,vault)
        settings.update(AiConfig(enabled = true,automatic = automatic,consent = true,hasKey = true))
        return ContextAi(app,settings,AiClient { _,_, payload ->
            calls.incrementAndGet(); requested += payload.getString("paragraph"); gate?.await()
            val terms = payload.getJSONArray("terms"); val rows = JSONArray()
            for(i in 0 until terms.length()) {
                val t = terms.getJSONObject(i)
                rows.put(JSONObject().put("term_id",t.getString("term_id")).put("original",t.getString("original"))
                    .put("current_translation",t.getString("current_translation")).put("needs_correction",true)
                    .put("suggested_translation","улика").put("explanation","Значение в контексте.").put("confidence","high"))
            }
            AiReply(JSONObject().put("results",rows).toString(),25,true)
        }).also { ai = it }
    }
    private fun await(condition: () -> Boolean) { compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() } }
    @Before fun setup() {
        Languages.current = Languages.SPANISH
        AppearanceStore.update { it.copy(mode = ReadingMode.SCROLL,gloss = GlossMode.UNKNOWN,markUnknown = true) }
        app.getSharedPreferences("wiki_reading_positions",0).edit().clear().commit()
        app.getSharedPreferences("context_ai",0).edit().clear().commit()
        runBlocking { withContext(Dispatchers.IO) { LexiconDatabase.get(app).clearAllTables() } }
    }
    @After fun cleanup() {
        if (::ai.isInitialized) ai.settings.update(AiConfig())
        if (::vault.isInitialized) vault.delete()
        Languages.current = previousLanguage; AppearanceStore.update { previousAppearance }
    }
    @Test fun tapChecksAWordShowsAndUndoesItsCorrectionWithoutRebillingOnReopen() {
        val ai = makeAi(false)
        val doc = WikiDocument(article,listOf(WikiBlock("pista para investigar.")))
        var showing by mutableStateOf(true)
        compose.setContent { LexiconTheme { if(showing) WikipediaReader(doc,source,dictionary,emptySet(),{ showing=false },aiOverride=ai) } }
        fun choose() {
            compose.onNodeWithTag("wiki-article-list").performScrollToIndex(1)
            compose.onNodeWithTag("wiki-block-0").performTouchInput { click(Offset(width*.22f,height*.15f)) }
            await { compose.onAllNodesWithText("Уточнить с DeepSeek").fetchSemanticsNodes().isNotEmpty() }
        }
        choose(); compose.onNodeWithText("Уточнить с DeepSeek").performScrollTo().performClick()
        await { compose.onAllNodesWithText("улика",substring=false).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1,calls.get())
        compose.onNodeWithText("А+").performClick()
        compose.onNodeWithText("улика",substring=false).assertExists()
        compose.onNodeWithText("Отменить исправление ИИ").performScrollTo().performClick()
        await { compose.onAllNodesWithText("дорожка",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("← Назад").performClick()
        compose.runOnIdle { showing=true }
        choose(); compose.onNodeWithText("Уточнить с DeepSeek").performScrollTo().performClick()
        await { compose.onAllNodesWithText("Исправление отклонено.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1,calls.get())
    }
    @Test fun automaticChecksOnlyTheVisibleParagraphAndReusesItsCacheAfterFontChangeAndReopen() {
        val ai = makeAi(true)
        val long = (1..65).joinToString(" ") { "pista para investigar el caso" }
        val doc = WikiDocument(article,List(20) { WikiBlock("Fragmento $it $long") })
        var showing by mutableStateOf(true)
        compose.setContent { LexiconTheme { if(showing) WikipediaReader(doc,source,dictionary,emptySet(),{ showing=false },aiOverride=ai) } }
        await { compose.onAllNodesWithTag("wiki-article-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wiki-article-list").performScrollToIndex(1)
        await { calls.get() == 1 && !ai.repository.busy.value }
        assertEquals(1,requested.size); assertTrue(requested.single().startsWith("Fragmento 0"))
        compose.onNodeWithText("А+").performClick()
        compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
        assertEquals(1,calls.get())
        compose.onNodeWithText("← Назад").performClick(); compose.runOnIdle { showing=true }
        compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
        assertEquals(1,calls.get())
        compose.onNodeWithTag("wiki-article-list").performScrollToIndex(11)
        await { requested.any { it.startsWith("Fragmento 10") } }
        assertEquals(2,calls.get())
    }
    @Test fun aPageWithOnlyAPictureDoesNotCreateAutomaticPaidRequests() {
        val ai = makeAi(false)
        AppearanceStore.update { it.copy(mode=ReadingMode.PAGES) }
        val image = WikiImage("https://upload.wikimedia.org/wikipedia/commons/a/ab/Photo.png","File:Photo.png","",200,900,0)
        val doc = WikiDocument(article,listOf(WikiBlock("pista para investigar.")),listOf(image))
        compose.setContent { LexiconTheme { WikipediaReader(doc,source,dictionary,emptySet(),{},aiOverride=ai) } }
        await { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wiki-pages").performTouchInput { swipeRight() }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.runOnIdle { ai.settings.update(ai.settings.config.value.copy(automatic=true)) }
        compose.mainClock.advanceTimeBy(1800); compose.waitForIdle()
        assertEquals(0,calls.get())
    }
    @Test fun closingTheArticleCancelsItsPendingManualRequest() {
        val ai = makeAi(false); gate = CompletableDeferred()
        val doc = WikiDocument(article,listOf(WikiBlock("pista para investigar.")))
        var showing by mutableStateOf(true)
        compose.setContent { LexiconTheme { if(showing) WikipediaReader(doc,source,dictionary,emptySet(),{ showing=false },aiOverride=ai) } }
        compose.onNodeWithTag("wiki-article-list").performScrollToIndex(1)
        compose.onNodeWithTag("wiki-block-0").performTouchInput { click(Offset(width*.22f,height*.15f)) }
        await { compose.onAllNodesWithText("Уточнить с DeepSeek").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Уточнить с DeepSeek").performScrollTo().performClick()
        await { calls.get()==1 }
        compose.onNodeWithText("← Назад").performClick()
        await { !ai.repository.busy.value }
        gate!!.complete(Unit)
        assertTrue(runBlocking { LexiconDatabase.get(app).aiCheckDao().all().isEmpty() })
    }
}
