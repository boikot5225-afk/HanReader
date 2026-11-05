package com.hanreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewQueueTest {
    private fun card(id: Long, introduced: Long = 0L, nextRec: Long = 0L,
                     nextWrite: Long = 0L) = WordCard(
        id = id, language = "fr", word = "mot$id", reading = "",
        translation = "слово", example = "Un mot.", exampleTranslation = "Слово.",
        createdAt = id * 100L, introducedAt = introduced,
        nextReviewAt = nextRec, writingNextReviewAt = nextWrite
    )
    private val now = 50_000L
    private val today = 30_000L

    @Test fun configurableDailyLimitCanBeTwenty() {
        val unseen = (1L..28L).map { card(it) }
        val plan = ReviewQueue.build(unseen, ReviewMode.WRITING, now, today, dailyLimit = 20)
        assertEquals(20, plan.due.size)
        assertEquals(20, plan.remainingNew)
    }

    @Test fun loweringDailyLimitKeepsOldReviews() {
        val dueOld = (1L..10L).map { card(it, 35_000L, nextRec = 40_000L) }
        val plan = ReviewQueue.build(
            dueOld + card(11L), ReviewMode.RECOGNITION, now, today, dailyLimit = 5
        )
        assertEquals(10, plan.due.size)
        assertEquals(0, plan.remainingNew)
    }

    @Test fun limitsNewWordsToTenAcrossModes() {
        val unseen = (1L..15L).map { card(it) }
        val reading = ReviewQueue.build(unseen, ReviewMode.RECOGNITION, now, today)
        val writing = ReviewQueue.build(unseen, ReviewMode.WRITING, now, today)
        assertEquals((1L..10L).toList(), reading.due.map { it.id })
        assertEquals(reading.due.map { it.id }, writing.due.map { it.id })
        assertEquals(15, reading.unseenTotal)
    }

    @Test fun twoIntroducedTodayLeaveEightNewSlotsForEitherMode() {
        val cards = listOf(card(1, 31_000L, nextRec = 70_000), card(2, 40_000L, nextRec = 90_000)) +
            (3L..17L).map { card(it) }
        val planRec = ReviewQueue.build(cards, ReviewMode.RECOGNITION, now, today)
        val planWrite = ReviewQueue.build(cards, ReviewMode.WRITING, now, today)
        assertEquals(2, planRec.introducedToday)
        assertEquals(8, planRec.remainingNew)
        assertEquals(8, planRec.due.size)
        assertEquals(10, planWrite.due.size)
        assertEquals(listOf(1L, 2L), planWrite.due.take(2).map { it.id })
    }

    @Test fun dueOlderReviewsContinueEvenWhenDailyLimitReached() {
        val cards = (1L..10L).map { card(it, 35_000L, nextRec = 60_000L) } +
            listOf(card(11, introduced = 10_000L, nextRec = 40_000L)) +
            (12L..20L).map { card(it) }
        val plan = ReviewQueue.build(cards, ReviewMode.RECOGNITION, now, today)
        assertEquals(10, plan.introducedToday)
        assertEquals(0, plan.remainingNew)
        assertEquals(listOf(11L), plan.due.map { it.id })
    }

    @Test fun separateModesHaveSeparateDueDates() {
        val known = card(1L, introduced = 10_000L, nextRec = 80_000L, nextWrite = 0L)
        val r = ReviewQueue.build(listOf(known), ReviewMode.RECOGNITION, now, today)
        val w = ReviewQueue.build(listOf(known), ReviewMode.WRITING, now, today)
        assertTrue(r.due.isEmpty())
        assertEquals(listOf(1L), w.due.map { it.id })
    }

    @Test fun previouslyIntroducedCardsDoNotConsumeTodaysQuota() {
        val old = card(1L, introduced = 10_000L, nextRec = 60_000L)
        val plan = ReviewQueue.build(listOf(old, card(2L)), ReviewMode.RECOGNITION, now, today)
        assertEquals(0, plan.introducedToday)
        assertEquals(listOf(2L), plan.due.map { it.id })
    }
}
