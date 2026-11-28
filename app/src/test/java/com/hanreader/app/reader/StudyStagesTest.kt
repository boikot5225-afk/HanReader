package com.hanreader.app.reader

import com.hanreader.app.ReviewMode
import com.hanreader.app.ReviewSchedule
import com.hanreader.app.WordCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyStagesTest {
    private val day = 86_400_000L
    private fun card(word: String = "银行", translation: String = "банк; берег", createdAt: Long = 0) =
        WordCard(language = "zh", word = word, reading = "yín háng", translation = translation, example = "", exampleTranslation = "", createdAt = createdAt)

    @Test fun aNewCardKeepsItsHintUntilTwoReviewsAndAFailureBringsItBack() {
        val fresh = card()
        assertEquals(mapOf("银行" to "банк"), StudyStages.hints(listOf(fresh)))
        val once = ReviewSchedule.rate(fresh, true, 0, ReviewMode.RECOGNITION)
        assertEquals(StudyStage.LEARNING, StudyStages.of(once))
        val twice = ReviewSchedule.rate(once, true, 2 * day, ReviewMode.RECOGNITION)
        assertEquals(StudyStage.RECALL, StudyStages.of(twice))
        assertTrue(StudyStages.hints(listOf(twice)).isEmpty())
        val forgot = ReviewSchedule.rate(twice, false, 10 * day, ReviewMode.RECOGNITION)
        assertTrue(StudyStages.of(forgot).hint)
        assertEquals(StudyStage.LEARNED, StudyStages.of(fresh.copy(intervalDays = 30, successfulReviews = 5, introducedAt = 1)))
    }

    @Test fun lookingUpAWordWithoutItsHintBringsTheReviewWithinADay() {
        val recall = card().copy(successfulReviews = 3, intervalDays = 7, introducedAt = 1, nextReviewAt = 7 * day)
        assertEquals(day, StudyStages.peeked(recall, 0)!!.nextReviewAt)
        assertEquals(3, StudyStages.peeked(recall, 0)!!.successfulReviews)
        assertNull(StudyStages.peeked(card(), 0))
        assertNull(StudyStages.peeked(recall.copy(nextReviewAt = day / 2), 0))
        assertFalse(StudyStages.canConfirm(recall, 0))
    }

    @Test fun anotherMeaningOnlyWhenNoneOfTheCardsMeaningsFits() {
        val bank = card()
        assertFalse(StudyStages.otherMeaning(bank, "Берег"))
        assertFalse(StudyStages.otherMeaning(bank, ""))
        assertTrue(StudyStages.otherMeaning(bank, "ряд"))
        val row = card(translation = "ряд", createdAt = 5)
        assertEquals(row, StudyStages.cardFor(listOf(bank, row), "ряд"))
        assertEquals(row, StudyStages.cardFor(listOf(bank, row), ""))
        assertEquals("очень длинное значение слов…", StudyStages.hint("очень длинное значение слова для подсказки"))
    }
}
