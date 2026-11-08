package com.hanreader.app.reader

import com.hanreader.app.DailyActivity
import java.util.Calendar

/** Days as yyyymmdd numbers in local time, streaks of active days, chart series. */
object ReadingStats {
    /** A day counts for the streak with a minute of reading or any card review. */
    const val ACTIVE_MILLIS = 60_000L

    /** Time on one page that counts as reading: longer is the phone left open. */
    const val MAX_PAGE_MILLIS = 3 * 60_000L

    fun dayOf(millis: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    fun today(): Int = dayOf(System.currentTimeMillis())

    /** The [n] days ending with [last], oldest first. */
    fun lastDays(last: Int, n: Int): List<Int> {
        val c = calendar(last)
        val out = ArrayList<Int>(n)
        repeat(n) {
            out += dayOf(c.timeInMillis)
            c.add(Calendar.DAY_OF_MONTH, -1)
        }
        return out.reversed()
    }

    private fun calendar(day: Int) = Calendar.getInstance().apply {
        clear()
        set(day / 10_000, day / 100 % 100 - 1, day % 100, 12, 0)
    }

    private fun previous(day: Int): Int = calendar(day).apply { add(Calendar.DAY_OF_MONTH, -1) }.let { dayOf(it.timeInMillis) }

    fun isActive(d: DailyActivity) = d.readMillis >= ACTIVE_MILLIS || d.reviews > 0

    data class Streak(val current: Int, val best: Int)

    /**
     * Consecutive active days. Today not active yet keeps yesterday's streak alive (the day is
     * not over); a missed day breaks it.
     */
    fun streak(days: List<DailyActivity>, today: Int): Streak {
        val active = days.filter { isActive(it) }.mapTo(HashSet()) { it.day }
        var current = 0
        var d = if (today in active) today else previous(today)
        while (d in active) { current++; d = previous(d) }
        var best = 0
        for (day in active) {
            if (previous(day) in active) continue // not the start of a run
            var len = 0
            var x = day
            while (x in active) { len++; x = calendar(x).apply { add(Calendar.DAY_OF_MONTH, 1) }.let { dayOf(it.timeInMillis) } }
            best = maxOf(best, len)
        }
        return Streak(current, maxOf(best, current))
    }

    /**
     * How readable a text is from the share of word occurrences the reader knows (Nation: about
     * 95-98% for comfortable reading). Null below any data.
     */
    enum class Difficulty(val title: String) {
        COMFORTABLE("комфортно"), WITH_HINTS("с подсказками"), HARD("трудно"), TOO_HARD("пока рано");

        companion object {
            fun of(percent: Int): Difficulty = when {
                percent >= 95 -> COMFORTABLE
                percent >= 90 -> WITH_HINTS
                percent >= 80 -> HARD
                else -> TOO_HARD
            }
        }
    }

    /** Characters read: milestones that mark progress. */
    val MILESTONES = listOf(10_000L, 50_000L, 100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L)

    fun nextMilestone(total: Long): Long = MILESTONES.firstOrNull { it > total } ?: ((total / 5_000_000L) + 1) * 5_000_000L

    /** "128 тыс.", "1,2 млн", "940" for a count of characters. */
    fun amount(n: Long): String = when {
        n >= 1_000_000L -> String.format(java.util.Locale("ru"), "%.1f млн", n / 1_000_000.0).replace(",0 ", " ")
        n >= 10_000L -> "${n / 1_000} тыс."
        n >= 1_000L -> String.format(java.util.Locale("ru"), "%.1f тыс.", n / 1_000.0).replace(",0 ", " ")
        else -> n.toString()
    }

    /** "6 нояб." for a day number. */
    fun label(day: Int): String {
        val months = listOf("янв.", "февр.", "марта", "апр.", "мая", "июня", "июля", "авг.", "сент.", "окт.", "нояб.", "дек.")
        return "${day % 100} ${months[day / 100 % 100 - 1]}"
    }
}
