package com.hanreader.app.reader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.Speaker
import com.hanreader.app.es.EsEngineTest
import com.hanreader.app.lang.Languages
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.zh.ZhContextRegressionTest
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the actual WordSheet, with original examples and the real language engines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComfortCardTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private lateinit var speaker: Speaker
    private val language=Languages.current
    private var savedMeaning: String?=null
    private var observedMeanings=emptyMap<String,String>()
    @Before fun setup() { speaker=Speaker(app); Doubts.clear(app); ReadingChecks.reset(app) }
    @After fun cleanup() { speaker.close(); Languages.current=language }

    private fun show(engine: LanguageModule, text: String, target: String, initialMeanings: Map<String,String> = emptyMap()) {
        val prepared=engine.chapter(listOf(text),TextOptions())(text)
        val block=ReaderBlock.Text(prepared.text,false,prepared.tokens)
        val i=prepared.tokens.indexOfFirst { it.text==target }; assertTrue(i>=0)
        compose.setContent {
            var selection by remember { mutableStateOf(Selection(0,i,i)) }
            var meanings by remember { mutableStateOf(initialMeanings) }
            WordSheet(selection,block,engine,emptySet(),emptySet(),{false},{flowOf(null)},speaker,
                onKnown={_,_->},onClearKnown={},highlight=null,onHighlight={_,_,_->},onNote={_,_->},onRemoveHighlight={},
                userMeanings=meanings,meaningKey={scope,key->"${scope.name}:$key"},onUserMeaning={key,value->meanings=if (value.isBlank()) meanings-key else meanings+(key to value);observedMeanings=meanings},
                onChange={selection=it},onDismiss={},onSave={_,_,meaning,_,done->savedMeaning=meaning;done(Result.success(true))},doubtSource="Свой пример")
        }
        compose.waitForIdle()
    }
    @Test fun alternativesAreHiddenUntilRequested() {
        compose.setContent { MeaningSummary("банда",listOf("банда","музыкальная группа"),false,null,true) }
        compose.onNodeWithText("банда").assertExists()
        compose.onNodeWithText("музыкальная группа").assertDoesNotExist()
        compose.onNodeWithText("Несколько значений · уточни по предложению").assertExists()
        compose.onNodeWithText("Другие значения (1)").performClick()
        compose.onNodeWithText("музыкальная группа").assertExists()
    }
    @Test fun wrongMeaningCanBeCorrectedHereAndSavedFromTheActualChineseCard() {
        Languages.current=Languages.CHINESE
        show(ZhContextRegressionTest.engine,"妖精来到银行。","妖精")
        compose.onNodeWithText("Не подходит").performClick()
        compose.onNodeWithText("Здесь").assertIsSelected()
        compose.onNodeWithText("Моё значение").performTextReplacement("мой гоблин")
        compose.onNodeWithText("Сохранить",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("мой гоблин").assertExists()
        val flag=Doubts.all(app).single()
        assertNotEquals("мой гоблин",flag.shown)
        assertEquals("мой гоблин",flag.correction)
        assertEquals("Здесь",flag.correctionScope)
        compose.onNodeWithText("Сохранить в карточки").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("мой гоблин",savedMeaning) }
        assertEquals(1,ReadingChecks.load(app).opened)
        assertEquals(1,ReadingChecks.load(app).flagged)
        assertEquals(1,ReadingChecks.load(app).corrected)
    }
    @Test fun spanishSelectionExpandsThroughASpaceWithoutCountingAnotherLookup() {
        Languages.current=Languages.SPANISH
        show(EsEngineTest.load(),"Una casa grande.","casa")
        compose.onNodeWithText("Инструменты").performClick()
        compose.onNodeWithText("слово →").performClick()
        compose.onNodeWithText("casa grande",substring=false).assertExists()
        compose.onNodeWithText("Убрать справа").performClick()
        assertEquals(1,ReadingChecks.load(app).opened)
        assertEquals(2,ReadingChecks.load(app).boundaries)
    }
    @Test fun notSuitableDefaultsToHereEvenWithAnExistingBookMeaning() {
        Languages.current=Languages.CHINESE
        show(ZhContextRegressionTest.engine,"妖精来到银行。","妖精",mapOf("BOOK:妖精" to "общее значение книги"))
        compose.onNodeWithText("Не подходит").performClick()
        compose.onNodeWithText("Здесь").assertIsSelected()
        compose.onNodeWithText("Моё значение").performTextReplacement("только в этом месте")
        compose.onNodeWithText("Сохранить",substring=false).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("общее значение книги",observedMeanings["BOOK:妖精"])
            assertEquals("только в этом месте",observedMeanings["HERE:妖精"])
        }
    }

}
