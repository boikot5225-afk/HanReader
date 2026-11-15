package com.hanreader.app.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.BuildConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ReadingFeedbackTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Before fun clean() { Doubts.clear(context); ReadingChecks.reset(context) }
    @Test fun correctionKeepsTheOriginallyShownAnswerAndRequiresAnExistingFlag() {
        Doubts.correct(context,"plaza","La plaza.","площадь",MeaningScope.HERE)
        assertTrue(Doubts.all(context).isEmpty())
        Doubts.add(context,Doubts.Doubt("plaza","plaza","территория","La plaza.","Свой пример",at=123))
        Doubts.correct(context,"plaza","La plaza."," площадь ",MeaningScope.HERE)
        val d=Doubts.all(context).single()
        assertEquals("территория",d.shown)
        assertEquals("площадь",d.correction)
        assertEquals("Здесь",d.correctionScope)
        assertEquals(123L,d.at)
        val export=Doubts.message("Amauta",listOf(d))
        assertTrue(export.contains("территория"));assertTrue(export.contains("Исправлено: «площадь»"))
    }
    @Test fun oldFlagsRemainReadableWithoutNewFields() {
        context.getSharedPreferences("doubts",Context.MODE_PRIVATE).edit().putString("list","""[{"word":"字","key":"字","shown":"знак","sentence":"字。","source":"пример","at":1}]""").commit()
        val d=Doubts.all(context).single()
        assertEquals("字",d.word);assertEquals("",d.correction)
    }
    @Test fun placeCorrectionKeepsBroaderFallbacksAndOutwardEditsUnhideTheNewValue() {
        val keys=MeaningScope.entries.associateWith { it.name }
        assertEquals(mapOf("HERE" to "площадь"),ContextMeanings.edits(keys,MeaningScope.HERE,"площадь"))
        assertEquals(mapOf("HERE" to "", "BOOK" to "площадь"),ContextMeanings.edits(keys,MeaningScope.BOOK,"площадь"))
        assertEquals(mapOf("HERE" to "", "BOOK" to "", "EVERYWHERE" to "площадь"),ContextMeanings.edits(keys,MeaningScope.EVERYWHERE,"площадь"))
        assertEquals(mapOf("BOOK" to ""),ContextMeanings.edits(keys,MeaningScope.BOOK,""))
    }
    @Test fun countersStayLocalAndSeparateFromFlags() {
        ReadingChecks.record(context,ReadingChecks.Event.OPEN)
        ReadingChecks.record(context,ReadingChecks.Event.BOUNDARY)
        ReadingChecks.record(context,ReadingChecks.Event.FLAG)
        ReadingChecks.record(context,ReadingChecks.Event.CORRECT)
        assertEquals(ReadingChecks.Counts(1,1,1,1),ReadingChecks.load(context))
        assertTrue(Doubts.all(context).isEmpty())
        ReadingChecks.awaitWrites()
        val persisted=org.json.JSONObject(java.io.File(context.noBackupFilesDir,"reading-checks-${BuildConfig.VERSION_NAME}.json").readText())
        assertEquals(1,persisted.getInt("opened"))
        ReadingChecks.reset(context)
        ReadingChecks.awaitWrites()
        assertEquals(ReadingChecks.Counts(),ReadingChecks.load(context))
        val file=java.io.File(context.noBackupFilesDir,"reading-checks-${BuildConfig.VERSION_NAME}.json")
        val data=org.json.JSONObject(file.readText())
        assertEquals(setOf("opened","flagged","corrected","boundaries"),data.keys().asSequence().toSet())
        assertTrue(data.keys().asSequence().all { data.get(it) is Int })
    }
}
