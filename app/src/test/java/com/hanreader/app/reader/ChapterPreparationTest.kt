package com.hanreader.app.reader

import com.hanreader.app.en.EnEngineTest
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.es.EsEngineTest
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChapterPreparationTest {
    private fun chapter(text: String, engine: LanguageModule) =
        ChapterBuilder.build(TxtBook(text.toByteArray(), "Preparation"), 0, engine)

    @Test fun spanishFormsAndRepeatedSelectionCountOncePerOccurrence() {
        val engine = EsEngineTest.load()
        val ch = chapter("Hablo y hablamos. Ella hablaba. El perro duerme.", engine)
        val known = setOf("y", "ella", "el")
        val plan = ChapterPreparation.of(ch, known, emptySet(), engine)
        assertEquals(3, plan.unknownOccurrences["hablar"])
        val current = KnownCoverage.of(ch, KnownMatcher(known, engine), engine = engine)
        assertEquals(current.total, plan.coverage.total)
        assertEquals(current.known, plan.coverage.known)
        val after = plan.afterLearning(listOf("hablar", "hablar", "not-in-chapter"))
        assertEquals(current.known + 3, after.known)
        assertEquals(plan.coverage.unknownDistinct - 1, after.unknownDistinct)
        assertFalse("hablar" in known) // a forecast does not mark anything known
        assertEquals(plan.coverage, plan.afterLearning(emptyList()))
    }

    @Test fun englishInflectionsAreOneUnitAndSavedWordsDoNotBecomeKnown() {
        val engine = EnEngineTest.engine
        val ch = chapter("A teacher and teachers. A teacher is here.", engine)
        val plan = ChapterPreparation.of(ch, setOf("a", "and", "be", "here"), emptySet(), engine)
        assertEquals(3, plan.unknownOccurrences["teacher"])
        val candidates = VocabularyStats.chapter(ch, emptySet(), setOf("teacher"), emptySet(), engine)
        assertFalse(candidates.any { it.word == "teacher" })
        assertTrue(plan.coverage.known < plan.coverage.total)
        assertEquals(plan.coverage.known + 3, plan.afterLearning(setOf("teacher")).known)
    }

    @Test fun chineseNamesIgnoredWordsAndKnownRepeatsMatchReaderCoverage() {
        val hsk = File("src/main/assets/zh/hsk.tsv").bufferedReader().useLines { HskLevels.parse(it) }
        val engine = ZhEngine(ZhSegmenterTest.dict, 0, hsk)
        val ch = chapter("他去图书馆。图书馆在北京。他终于来了。", engine)
        val known = setOf("他", "去", "在", "了")
        val names = setOf("北京", "终于")
        val plan = ChapterPreparation.of(ch, known, names, engine)
        val base = KnownCoverage.of(ch, KnownMatcher(known, engine), names, engine)
        assertEquals(base.total, plan.coverage.total)
        assertEquals(base.known, plan.coverage.known)
        assertEquals(2, plan.unknownOccurrences["图书馆"])
        assertFalse("北京" in plan.unknownOccurrences)
        assertFalse("终于" in plan.unknownOccurrences)
        assertEquals(base.known + 2, plan.afterLearning(listOf("图书馆", "他", "北京")).known)
        assertEquals(100, plan.afterLearning(plan.unknownOccurrences.keys).percent)
    }

    @Test fun explicitUnknownAndEmptyChapterAreHandled() {
        val engine = EsEngineTest.load()
        val ch = chapter("Hablo y hablamos.", engine)
        val known = KnownWords(setOf("hablar", "y"), setOf("Hablo"))
        val plan = ChapterPreparation.of(ch, known, emptySet(), engine)
        assertEquals(1, plan.unknownOccurrences["hablar"])
        assertEquals(plan.coverage.known, plan.afterLearning(setOf("hablar")).known)
        assertEquals(plan.coverage.total, plan.afterLearning(setOf("hablar", "Hablo")).known)
        val empty = ChapterPreparation.of(ChapterContent(0, "", emptyList(), 0, 0), emptySet(), emptySet(), engine)
        assertEquals(0, empty.afterLearning(setOf("hablar")).percent)
    }

    @Test fun ambiguousFormsForecastMatchesActualReaderMarks() {
        val engine = EsEngineTest.load()
        val ch = chapter("Hablo y hablamos. Ella habla.", engine)
        val known = setOf("y", "ella")
        val plan = ChapterPreparation.of(ch, known, emptySet(), engine)
        val actual = KnownCoverage.of(ch, KnownMatcher(known + "hablar", engine), engine = engine)
        assertEquals(actual.known, plan.afterLearning(setOf("hablar")).known)
        assertEquals(actual.percent, plan.afterLearning(setOf("hablar")).percent)
    }
}
