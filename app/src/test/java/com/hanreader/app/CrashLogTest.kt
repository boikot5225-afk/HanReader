package com.hanreader.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLogTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun crashesAndErrorsAreKeptUntilCleared() {
        CrashLog.clear(app)
        var systemHandlerCalled = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> systemHandlerCalled = true }
        CrashLog.install(app)

        CrashLog.record("Открытие главы 3", IllegalStateException("broken chapter"))
        assertFalse("an error the app survived is not a crash", CrashLog.hasCrash(app))

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), ClassCastException("Text cannot be cast to Image"))
        assertTrue("the system's handler still runs", systemHandlerCalled)
        assertTrue(CrashLog.hasCrash(app))
        assertEquals(2, CrashLog.reports(app).size)
        val text = CrashLog.text(app)
        assertTrue(text, text.contains("HanReader ") && text.contains("Android "))
        assertTrue(text, text.contains("Тип: вылет") && text.contains("ClassCastException"))
        assertTrue(text, text.contains("Тип: ошибка без вылета") && text.contains("Открытие главы 3"))

        // At most 20 reports are kept.
        repeat(25) { CrashLog.record("x", RuntimeException("e$it")) }
        assertTrue(CrashLog.reports(app).size <= 20)

        CrashLog.clear(app)
        assertEquals(0, CrashLog.reports(app).size)
        Thread.setDefaultUncaughtExceptionHandler(null)
    }
}
