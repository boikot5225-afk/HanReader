package com.hanreader.app

/** Pure Russian text helpers for counters in the UI. */
object RussianText {
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }

    fun days(n: Int): String = "$n " + plural(n, "день", "дня", "дней")

    /** Short relative duration: "5 мин", "3 ч", "2 дня". */
    fun duration(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(1L)
        return when {
            minutes < 60 -> "$minutes мин"
            minutes < 60 * 24 -> "${minutes / 60} ч"
            else -> days((minutes / (60 * 24)).toInt())
        }
    }
}
