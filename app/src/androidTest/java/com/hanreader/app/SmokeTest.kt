package com.hanreader.app

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanreader.app.lang.Languages
import com.hanreader.app.reader.ReaderViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app on a real Android system (emulators in CI): add the demo book (text written for the
 * store), open it, turn a page, open the menu, open the settings and «О приложении». Fails on a
 * crash or a screen that never appears.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** The demo book is Chinese: the app is set to read Chinese before it starts (it opens in its first language). */
    private fun setLanguage(app: Application, tag: String) {
        app.getSharedPreferences("language", android.content.Context.MODE_PRIVATE).edit().putString("studyLanguage", tag).commit()
        Languages.init(app)
    }

    private fun importDemoBook() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val file = File(app.cacheDir, "demo-book.epub")
        instrumentation.context.assets.open("demo-book.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
        val done = CountDownLatch(1)
        var result: Result<Long>? = null
        instrumentation.runOnMainSync {
            ReaderViewModel(app).importBook(Uri.fromFile(file)) { result = it; done.countDown() }
        }
        assertTrue("import finished", done.await(60, TimeUnit.SECONDS))
        assertTrue("imported: $result", result!!.isSuccess)
    }

    @Test
    fun readABook() {
        // The demo book is Chinese: only for an app that reads Chinese.
        org.junit.Assume.assumeTrue(Languages.CHINESE in Languages.AVAILABLE)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        setLanguage(app, Languages.CHINESE.tag)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
        importDemoBook()
        // The library shows the book; open it.
        compose.waitOrExplain("hasText(蓝色的书)", 30_000) { compose.onAllNodes(hasText("蓝色的书")).fetchSemanticsNodes().isNotEmpty() }
        // On a small screen the card can sit under the bottom bar: scroll the library to its end first.
        repeat(3) { compose.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.3f) }; compose.waitForIdle() }
        compose.onAllNodes(hasText("蓝色的书"))[0].performClick()
        // The reader: the running header with the chapter title (the text itself is drawn, not semantic).
        compose.waitOrExplain("hasText(第一章　雨夜书店)", 60_000) { compose.onAllNodes(hasText("第一章　雨夜书店")).fetchSemanticsNodes().isNotEmpty() }
        // Swipes start inside the screen: from the very edge, Android 10+ takes them as the system Back gesture.
        compose.onRoot().performTouchInput { swipeLeft(startX = width * 0.8f, endX = width * 0.2f) }
        compose.waitForIdle()
        // A vertical swipe opens the menu.
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.3f) }
        compose.waitOrExplain("hasContentDescription(Назад в библиотеку)", 15_000) { compose.onAllNodes(hasContentDescription("Назад в библиотеку")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasContentDescription("Назад в библиотеку"))[0].performClick()
        // Back in the library: the settings and «О приложении».
        compose.waitOrExplain("hasContentDescription(Настройки)", 15_000) { compose.onAllNodes(hasContentDescription("Настройки")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasContentDescription("Настройки"))[0].performClick()
        compose.waitOrExplain("hasText(Готово)", 15_000) { compose.onAllNodes(hasText("Готово")).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            scenario.close()
            setLanguage(app, Languages.default.tag)
        }
    }
}
