package com.hanreader.app.en

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.Languages
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.reader.*
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishReaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val before = Languages.current
    private lateinit var speaker: Speaker
    private var saved: List<String>? = null
    private var marked: String? = null
    @Before fun setup() { Languages.current = Languages.ENGLISH; speaker = Speaker(app); Doubts.clear(app) }
    @After fun cleanup() { speaker.close(); Languages.current = before }

    private fun show(text: String, word: String) {
        val engine = EnEngineTest.engine
        val p = engine.chapter(listOf(text), TextOptions())(text)
        val i = p.tokens.indexOfFirst { it.text == word }
        assertTrue(i >= 0)
        compose.setContent {
            LexiconTheme {
                var selection by remember { mutableStateOf(Selection(0, i, i)) }
                WordSheet(selection, ReaderBlock.Text(text, false, p.tokens), engine, emptySet(), emptySet(),
                    { false }, { flowOf(null) }, speaker, onKnown = { word, known -> if (known) marked = word }, onClearKnown = {},
                    highlight = null, onHighlight = { _, _, _ -> }, onNote = { _, _ -> }, onRemoveHighlight = {},
                    onChange = { selection = it }, onDismiss = {},
                    onSave = { word, reading, translation, sentence, done ->
                        saved = listOf(word, reading, translation, sentence); done(Result.success(true))
                    }, doubtSource = "Свой пример")
            }
        }
        compose.waitForIdle()
    }

    @Test fun aPastVerbSavesTheLemmaPronunciationAndOriginalSentence() {
        show("She went slowly.", "went")
        compose.onNodeWithText("→ go").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        val root = dialog.window!!.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File("build/screens").mkdirs()
        File("build/screens/en-word-card.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Сохранить в карточки").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("go", saved?.get(0))
            assertTrue(saved!![1].contains("гл."))
            assertTrue(saved!![1].length > "гл.".length)
            assertEquals("She went slowly.", saved!![3])
        }
    }

    @Test fun contractionCardKeepsTheFullContextualExpansion() {
        show("I'd go.", "I'd")
        compose.onNodeWithText("я бы", substring = true).assertExists()
        compose.onNodeWithText("Знаю").performClick()
        compose.runOnIdle { assertEquals("I'd", marked) }
        compose.onNodeWithText("Сохранить в карточки").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("I'd", saved?.get(0))
            assertTrue(saved!![2].contains("I would"))
        }
    }

    @Test fun phraseHeadSavesTheWholeDictionaryExpression() {
        show("She looked up the word.", "looked")
        compose.onNodeWithText("Выражение: look up").assertExists()
        compose.onNodeWithText("Знаю").performClick()
        compose.runOnIdle { assertEquals("look up", marked) }
        compose.onNodeWithText("Сохранить в карточки").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("look up", saved?.get(0)) }
    }

    @Test fun englishAppearanceHasNoSpanishRegionControls() {
        compose.setContent { LexiconTheme { AppearanceSheet(onDismiss = {}) } }
        compose.onNodeWithText("Вариант испанского").assertDoesNotExist()
        compose.onNodeWithText("Цвет тонов").assertDoesNotExist()
    }
}
