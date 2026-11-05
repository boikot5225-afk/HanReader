package com.hanreader.app

enum class ReviewMode { RECOGNITION, WRITING }

object ReviewSchedule {
    private const val DAY_MS = 86_400_000L
    private const val RETRY_MS = 600_000L
    private val steps = listOf(1, 3, 7, 14, 30, 60)
    private const val ALREADY_KNOWN_DAYS = 30

    fun dueAt(card: WordCard, mode: ReviewMode): Long = when (mode) {
        ReviewMode.RECOGNITION -> card.nextReviewAt
        ReviewMode.WRITING -> card.writingNextReviewAt
    }

    fun successCount(card: WordCard, mode: ReviewMode): Int = when (mode) {
        ReviewMode.RECOGNITION -> card.successfulReviews
        ReviewMode.WRITING -> card.writingSuccessfulReviews
    }

    fun lapses(card: WordCard, mode: ReviewMode): Int = when (mode) {
        ReviewMode.RECOGNITION -> card.recognitionLapses
        ReviewMode.WRITING -> card.writingLapses
    }

    /** A failure triggers a short retry and loses at most two stages, not all progress. */
    fun rate(card: WordCard, remembered: Boolean, now: Long, mode: ReviewMode): WordCard {
        val firstEncounter = if (card.introducedAt == 0L) now else card.introducedAt
        val before = successCount(card, mode)
        val successes = if (remembered) before + 1 else (before - 2).coerceAtLeast(0)
        val days = if (remembered) steps[(successes - 1).coerceAtMost(steps.lastIndex)] else 0
        val next = if (remembered) now + days * DAY_MS else now + RETRY_MS
        val newLapses = lapses(card, mode) + if (remembered) 0 else 1
        return when (mode) {
            ReviewMode.RECOGNITION -> card.copy(
                introducedAt = firstEncounter,
                nextReviewAt = next,
                successfulReviews = successes,
                intervalDays = days,
                recognitionLapses = newLapses
            )
            ReviewMode.WRITING -> card.copy(
                introducedAt = firstEncounter,
                writingNextReviewAt = next,
                writingSuccessfulReviews = successes,
                writingIntervalDays = days,
                writingLapses = newLapses
            )
        }
    }

    /**
     * Mark only the current direction as familiar. -1 marks an already-known
     * word that must not consume today's quota, while keeping the other direction independent.
     * The word is still reviewed after 30 days and can be forgotten later.
     */
    fun markAlreadyKnown(card: WordCard, mode: ReviewMode, now: Long): WordCard {
        val days = ALREADY_KNOWN_DAYS
        val next = now + days * DAY_MS
        val introduced = if (card.introducedAt == 0L) -1L else card.introducedAt
        return when (mode) {
            ReviewMode.RECOGNITION -> card.copy(
                introducedAt = introduced,
                nextReviewAt = next,
                successfulReviews = maxOf(card.successfulReviews, 5),
                intervalDays = days
            )
            ReviewMode.WRITING -> card.copy(
                introducedAt = introduced,
                writingNextReviewAt = next,
                writingSuccessfulReviews = maxOf(card.writingSuccessfulReviews, 5),
                writingIntervalDays = days
            )
        }
    }
}
