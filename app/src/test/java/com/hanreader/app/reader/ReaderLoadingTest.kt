package com.hanreader.app.reader

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.lang.Languages
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    @Before fun setup() { Languages.current = Languages.ENGLISH }
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }
    private fun book(): Long = runBlocking { LexiconDatabase.get(app).bookDao().insert(
        BookEntity(title = "private book title", author = "private author", fileName = "private.epub", chapterCount = 1)) }

    @Test fun slowPanelHasStageCancelAndSafeReportWithoutWaitingForAMinute() {
        var closed = false
        val state = ReaderLoadState(ReaderLoadStage.DICTIONARY, startedAt = SystemClock.elapsedRealtime() - 15_000)
        compose.setContent { LexiconTheme { ReaderLoadingPanel(state, { closed = true }) } }
        compose.onNodeWithText("Готовлю словарь…").assertExists()
        compose.onNodeWithText("Это дольше обычного.", substring = true).assertExists()
        compose.onNodeWithText("Сохранить технический отчёт").assertExists()
        compose.onNodeWithText("В библиотеку").performClick()
        assertTrue(closed)
        val report = readerLoadReport(state, "secret key book text", state.startedAt + 15_000)
        assertTrue(report.contains("Elapsed milliseconds: 15000"))
        assertTrue(report.contains("Stage: DICTIONARY"))
        assertTrue(report.contains("Language: other"))
        assertFalse(report.contains("secret key book text"))
        assertEquals(0L, state.elapsed(state.startedAt - 1))
    }

    @Test fun errorPanelOffersExplicitRetryScrollAndLibraryWithoutAutomaticRetries() {
        var retries = 0; var scrolls = 0
        compose.setContent { LexiconTheme { ReaderLoadingPanel(
            ReaderLoadState(ReaderLoadStage.PAGES, failure = ReaderLoadFailure.TIMEOUT), {},
            onRetry = { retries++ }, onScroll = { scrolls++ }) } }
        compose.waitForIdle()
        assertEquals(0, retries)
        compose.onNodeWithText("Подготовка заняла слишком много времени.").assertExists()
        compose.onNodeWithText("Повторить").performClick()
        compose.onNodeWithText("Открыть прокруткой").performClick()
        assertEquals(1, retries); assertEquals(1, scrolls)
    }

    @Test fun reportExportSurvivesLoadingFinishingWhileDocumentPickerIsOpen() {
        val file = File(app.cacheDir, "loading-report-export.txt").apply { delete() }
        var loading by mutableStateOf(true)
        compose.setContent { LexiconTheme {
            val save = rememberReaderReportSaver()
            if (loading) ReaderLoadingPanel(ReaderLoadState(ReaderLoadStage.PAGES,
                failure = ReaderLoadFailure.PAGES), {}, onSaveReport = save)
            else Text("Книга открыта")
        } }
        compose.onNodeWithText("Сохранить технический отчёт").performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        compose.runOnIdle {
            loading = false
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(file)))
        }
        await { file.exists() && file.length() > 0 }
        val report = file.readText()
        assertTrue(report.contains("Stage: PAGES")); assertTrue(report.contains("Failure: PAGES"))
        assertFalse(report.contains("private.epub")); assertFalse(report.contains("private book title"))
        file.delete()
    }

    @Test fun timeoutStopsLoadingEvenIfFileProviderIgnoresCancellationAndLateResultCannotReopenBook() {
        val gate = CompletableDeferred<Unit>()
        val entered = java.util.concurrent.atomic.AtomicBoolean(false)
        var sourceClosed = false
        val underlying = TxtBook("A short test.".toByteArray(), "private book title")
        val source = object : com.hanreader.app.epub.BookSource by underlying {
            override fun close() { sourceClosed = true; underlying.close() }
        }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val vm = ReaderViewModel(app, loadTimeoutMs = 1000, bookOpener = {
            if (calls.incrementAndGet() == 1)
                withContext(NonCancellable) { entered.set(true); gate.await(); source }
            else TxtBook("Another book.".toByteArray(), "other")
        })
        compose.activity.viewModelStore.put("reader", vm)
        vm.openBook(book())
        await { entered.get() }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1001))
        await { vm.loadingState.value?.failure == ReaderLoadFailure.TIMEOUT }
        assertNull(vm.open.value)
        assertTrue(vm.openingError.value!!.contains("слишком много"))
        val next = book()
        vm.openBook(next)
        await { vm.open.value?.entity?.id == next }
        gate.complete(Unit)
        await { sourceClosed }
        assertEquals(next, vm.open.value!!.entity.id)
    }

    @Test fun cancelReturnsImmediatelyAndProviderFailureDoesNotExposeItsMessage() {
        val gate = CompletableDeferred<Unit>()
        val vm = ReaderViewModel(app, bookOpener = { gate.await(); error("private token and book text") })
        compose.activity.viewModelStore.put("reader", vm)
        vm.openBook(book())
        await { vm.loadingState.value != null }
        vm.closeBook()
        assertNull(vm.loadingState.value); assertNull(vm.open.value)
        gate.complete(Unit)
        compose.waitForIdle()
        assertNull(vm.open.value)
        val bad = ReaderViewModel(app, bookOpener = { error("private token and book text") })
        compose.activity.viewModelStore.put("bad", bad)
        bad.openBook(book())
        await { bad.openingError.value != null }
        assertEquals(ReaderLoadFailure.FILE, bad.loadingState.value!!.failure)
        assertFalse(bad.openingError.value!!.contains("private"))
    }
}
