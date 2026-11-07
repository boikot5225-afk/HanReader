package com.hanreader.app.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.DailyActivity
import com.hanreader.app.LexiconDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingStatsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun read(day: Int, minutes: Int) = DailyActivity(day, minutes * 60_000L, minutes * 200, minutes, 0)

    @Test
    fun daysAndLabels() {
        assertEquals(listOf(20261030, 20261031, 20261101, 20261102), ReadingStats.lastDays(20261102, 4))
        assertEquals(listOf(20241228, 20241229, 20241230, 20241231, 20250101), ReadingStats.lastDays(20250101, 5))
        assertEquals("6 нояб.", ReadingStats.label(20261106))
    }

    @Test
    fun streaks() {
        val days = listOf(
            read(20261025, 20), read(20261026, 5), read(20261027, 30), // a run of 3
            read(20261029, 0),                                       // opened but under a minute: not active
            read(20261031, 10), read(20261101, 12),
            DailyActivity(20261102, 0, 0, 0, reviews = 15)           // reviews only: active
        )
        // Today (Nov 3) not active yet: yesterday's run of 3 (Oct 31 - Nov 2, across the month) still counts.
        assertEquals(ReadingStats.Streak(current = 3, best = 3), ReadingStats.streak(days, 20261103))
        assertEquals(ReadingStats.Streak(current = 4, best = 4), ReadingStats.streak(days + read(20261103, 2), 20261103))
        // A missed day breaks it.
        assertEquals(0, ReadingStats.streak(days, 20261104).current)
        assertEquals(ReadingStats.Streak(0, 0), ReadingStats.streak(emptyList(), 20261104))
    }

    @Test
    fun readingAddsUpPerDay() = runBlocking {
        val dao = LexiconDatabase.get(app).statsDao()
        dao.addReading(20261106, 90_000, 300)
        dao.addReading(20261106, 30_000, 100)
        dao.addReview(20261106)
        dao.addReview(20261107)
        val rows = dao.allDays().associateBy { it.day }
        assertEquals(DailyActivity(20261106, 120_000, 400, 2, 1), rows[20261106])
        assertEquals(DailyActivity(20261107, 0, 0, 0, 1), rows[20261107])
    }

    @After
    fun cleanUp() = runBlocking { LexiconDatabase.get(app).statsDao().clearDays() }
}
