package com.hanreader.app.ai

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.*
import com.hanreader.app.reader.*
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    private lateinit var speaker: Speaker
    private var saved: Pair<String, String>? = null
    private var savedReading: String? = null
    private var undone = false
    private object Dictionary : LanguageModule {
        override val info = Languages.SPANISH
        override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText = { error("unused") }
        override fun glossShort(word: String, reading: String?) = "дорожка"
        override fun cardTranslation(word: String, reading: String?) = "дорожка"
    }
    @Before fun setup() { Languages.current = Languages.SPANISH; speaker = Speaker(app) }
    @After fun cleanup() { speaker.close(); Languages.current = previous }
    private fun show(expression: Boolean = false, manual: Boolean = false, chinese: Boolean = false, english: Boolean = false, missing: Boolean = false) {
        val text = when { missing -> "The globally-admired writer arrived."; chinese -> "他终于回来了。"; english -> "She sat on the bank by the river."
            expression -> "Se van identificando."; else -> "Buscaba una pista en los periódicos." }
        val engine = when {
            chinese -> ZhEngine(com.hanreader.app.zh.ZhSegmenterTest.dict, 0)
            english -> com.hanreader.app.en.EnEngineTest.engine
            else -> Dictionary
        }
        if (chinese) Languages.current = Languages.CHINESE
        if (english) Languages.current = Languages.ENGLISH
        val tokens = if (chinese || english) engine.chapter(listOf(text), TextOptions())(text).tokens
        else Regex("\\p{L}+|\\s+|[^\\p{L}\\s]").findAll(text).map { RToken(it.value, "",
            if (it.value.first().isLetter()) TokenKind.HAN else TokenKind.PUNCT, it.range.first, it.range.last + 1, true) }.toList()
        val target = when { missing -> "globally-admired"; chinese -> "回来"; english -> "bank"; expression -> "van"; else -> "pista" }
        val index = tokens.indexOfFirst { it.text == target }
        assertTrue(index >= 0)
        val start = if (expression) 0 else tokens[index].start
        val end = if (expression || chinese) text.length - 1 else tokens[index].end
        val translation = when { missing -> "всемирно признанный"; chinese -> "вернулся"; english -> "берег"; expression -> "постепенно отождествляются"; else -> "улика" }
        val row = AiCheck("ui", 1, 0, 0, "hash", tokens[index].start, tokens[index].end, start, end,
            text.substring(start, end), engine.glossShort(tokens[index]).orEmpty(), translation, "По контексту.", "high", "accepted")
        compose.setContent {
            LexiconTheme {
                var check by remember { mutableStateOf<AiCheck?>(row) }
                WordSheet(Selection(0, index, index), ReaderBlock.Text(text, false, tokens), engine, emptySet(), emptySet(),
                    { false }, { flowOf(null) }, speaker, onKnown = { _, _ -> }, onClearKnown = {}, highlight = null,
                    onHighlight = { _, _, _ -> }, onNote = { _, _ -> }, onRemoveHighlight = {},
                    aiCheck = check, aiAvailable = true, aiEnabled = true,
                    onAiState = { r, state -> undone = true; check = r.copy(state = state) },
                    userMeanings = if (manual) mapOf("here" to "мой перевод") else emptyMap(),
                    meaningKey = { scope, key -> if (scope == MeaningScope.HERE) "here" else key },
                    onChange = {}, onDismiss = {}, onSave = { word, reading, translation, _, done ->
                        saved = word to translation; savedReading = reading; done(Result.success(true))
                    })
            }
        }
    }

    @Test fun cardUsesAiMeaningAndShowsOriginalWithUndo() {
        show()
        compose.onNodeWithText("Исходная подсказка: дорожка").performScrollTo().assertExists()
        compose.onNodeWithText("Отменить исправление ИИ").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(undone) }
        compose.onNodeWithText("Сохранить в карточки").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("pista" to "дорожка", saved) }
    }

    @Test fun acceptedConstructionSavesTheExpressionInsteadOfAssigningItsMeaningToOneWord() {
        show(expression = true)
        compose.onNodeWithText("Сохранить выражение в карточки").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Se van identificando" to "постепенно отождествляются", saved) }
    }

    @Test fun manualMeaningRemainsTheSavedMeaningWhenAiExists() {
        show(manual = true)
        compose.onNodeWithText("Используется твоё значение. ИИ его не заменяет.").performScrollTo().assertExists()
        compose.onNodeWithText("Сохранить в карточки").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("pista" to "мой перевод", saved) }
    }

    @Test fun englishWordSavesTheContextualMeaningWithItsPronunciation() {
        show(english = true)
        compose.onNodeWithText("Сохранить в карточки").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("bank" to "берег", saved); assertTrue(!savedReading.isNullOrBlank()) }
    }

    @Test fun missingDictionaryWordShowsContextualMeaningAndSavesItToCards() {
        show(english = true, missing = true)
        compose.onNodeWithText("Исходное значение: в словаре отсутствует").performScrollTo().assertExists()
        compose.onNodeWithText("По контексту").performScrollTo().assertExists()
        compose.onNodeWithText("Сохранить в карточки").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("globally-admired" to "всемирно признанный", saved) }
    }

    @Test fun chineseConstructionWithoutSpacesSavesTheWholeSpanAndPreservesPinyin() {
        show(chinese = true)
        compose.onNodeWithText("Сохранить выражение в карточки").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("回来了" to "вернулся", saved); assertTrue(savedReading!!.contains("huí")) }
    }

    @Test fun settingsCanOpenWithoutKeyAndCannotSendAnUnapprovedConnectionRequest() {
        compose.setContent { LexiconTheme { AiSettingsDialog {} } }
        compose.onNodeWithText("Контекстный ИИ").assertExists()
        compose.onNodeWithText("Проверить подключение (платный запрос)").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            val c = ContextAi.get(app).settings.config.value
            assertFalse(c.enabled); assertFalse(c.automatic); assertFalse(c.consent)
            assertEquals(0, ContextAi.get(app).repository.usage.value.requests)
        }
    }
}
