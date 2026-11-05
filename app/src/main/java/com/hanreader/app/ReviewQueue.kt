package com.hanreader.app

import java.util.Calendar

/** Both directions share the daily quota for introducing new words, per language. */
data class ReviewPlan(
    val due: List<WordCard>,
    val introducedToday: Int,
    val unseenTotal: Int,
    val dailyLimit: Int
) {
    val remainingNew: Int get() = (dailyLimit - introducedToday).coerceAtLeast(0)
}

object ReviewQueue {
    const val DEFAULT_NEW_PER_DAY = 10

    fun localDayStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun build(
        cards: List<WordCard>,
        mode: ReviewMode,
        now: Long,
        dayStart: Long = localDayStart(now),
        dailyLimit: Int = DEFAULT_NEW_PER_DAY
    ): ReviewPlan {
        require(dailyLimit >= 0)
        val introducedToday = cards.count {
            it.introducedAt > 0 && it.introducedAt >= dayStart && it.introducedAt <= now
        }
        val remaining = (dailyLimit - introducedToday).coerceAtLeast(0)
        val dueOld = cards.asSequence()
            .filter { it.introducedAt != 0L && ReviewSchedule.dueAt(it, mode) <= now }
            .sortedWith(compareBy<WordCard> { ReviewSchedule.dueAt(it, mode) }.thenBy { it.id })
            .toList()
        val unintroduced = cards.asSequence()
            .filter { it.introducedAt == 0L }
            .sortedWith(compareBy<WordCard> { it.createdAt }.thenBy { it.id })
            .toList()
        return ReviewPlan(
            due = dueOld + unintroduced.take(remaining),
            introducedToday = introducedToday,
            unseenTotal = unintroduced.size,
            dailyLimit = dailyLimit
        )
    }
}
