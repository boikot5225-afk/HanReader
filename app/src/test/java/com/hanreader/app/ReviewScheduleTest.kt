package com.hanreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewScheduleTest {
    private val card = WordCard(
        language = "en", word = "consider", reading = "",
        translation = "рассматривать", example = "Consider this.",
        exampleTranslation = "Рассмотри это."
    )

    @Test fun recognitionSuccessKeepsWritingHistory() {
        val result = ReviewSchedule.rate(card, true, 1000L, ReviewMode.RECOGNITION)
        assertEquals(1, result.successfulReviews)
        assertEquals(86_401_000L, result.nextReviewAt)
        assertEquals(1000L, result.introducedAt)
        assertEquals(0, result.writingSuccessfulReviews)
    }

    @Test fun writingSuccessKeepsRecognitionHistory() {
        val prior = card.copy(nextReviewAt = 500_000L, successfulReviews = 3, introducedAt = 800L)
        val result = ReviewSchedule.rate(prior, true, 1000L, ReviewMode.WRITING)
        assertEquals(1, result.writingSuccessfulReviews)
        assertEquals(86_401_000L, result.writingNextReviewAt)
        assertEquals(3, result.successfulReviews)
        assertEquals(500_000L, result.nextReviewAt)
    }

    @Test fun errorRetainsMostLongTermProgressAndTracksLapses() {
        val prior = card.copy(
            introducedAt = 500L, successfulReviews = 5, intervalDays = 30,
            writingSuccessfulReviews = 4, writingIntervalDays = 14
        )
        val error = ReviewSchedule.rate(prior, false, 1000L, ReviewMode.RECOGNITION)
        assertEquals(3, error.successfulReviews)
        assertEquals(0, error.intervalDays)
        assertEquals(1, error.recognitionLapses)
        assertEquals(601_000L, error.nextReviewAt)
        assertEquals(4, error.writingSuccessfulReviews)
        val recovered = ReviewSchedule.rate(error, true, 601_000L, ReviewMode.RECOGNITION)
        assertEquals(4, recovered.successfulReviews)
        assertEquals(14, recovered.intervalDays)
        assertEquals(1, recovered.recognitionLapses)
    }

    @Test fun earlyMistakeAndWritingMistakeAreIndependent() {
        val before = card.copy(
            writingSuccessfulReviews = 1, successfulReviews = 4, recognitionLapses = 2
        )
        val result = ReviewSchedule.rate(before, false, 1000L, ReviewMode.WRITING)
        assertEquals(0, result.writingSuccessfulReviews)
        assertEquals(1, result.writingLapses)
        assertEquals(4, result.successfulReviews)
        assertEquals(2, result.recognitionLapses)
    }

    @Test fun markAlreadyKnownDoesNotConsumeQuotaOrAssumeWriting() {
        val result = ReviewSchedule.markAlreadyKnown(card, ReviewMode.RECOGNITION, 1000L)
        assertEquals(-1L, result.introducedAt)
        assertEquals(5, result.successfulReviews)
        assertEquals(30, result.intervalDays)
        assertEquals(2_592_001_000L, result.nextReviewAt)
        assertEquals(0, result.writingSuccessfulReviews)
        assertEquals(0L, result.writingNextReviewAt)
        val writing = ReviewSchedule.markAlreadyKnown(result, ReviewMode.WRITING, 2000L)
        assertEquals(30, writing.writingIntervalDays)
        assertEquals(result.nextReviewAt, writing.nextReviewAt)
        assertEquals(-1L, writing.introducedAt)
    }

    @Test fun markedKnownCanStillBeForgotten() {
        val known = ReviewSchedule.markAlreadyKnown(card, ReviewMode.WRITING, 1000L)
        val forgotten = ReviewSchedule.rate(known, false, 3000L, ReviewMode.WRITING)
        assertEquals(3, forgotten.writingSuccessfulReviews)
        assertEquals(1, forgotten.writingLapses)
        assertEquals(603_000L, forgotten.writingNextReviewAt)
        assertEquals(-1L, forgotten.introducedAt)
    }
}
