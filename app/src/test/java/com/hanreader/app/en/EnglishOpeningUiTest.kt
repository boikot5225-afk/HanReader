package com.hanreader.app.en

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.Languages
import com.hanreader.app.reader.ReaderScreen
import com.hanreader.app.reader.ReaderViewModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opens the real ReaderScreen with automatic levels, not just a prepared word card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishOpeningUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    private lateinit var vm: ReaderViewModel
    private var closed = false

    private fun awaitVm(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        compose.waitUntil(timeoutMs) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }

    @Before fun setup() {
        Languages.current = Languages.ENGLISH
        vm = ViewModelProvider(compose.activity, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[ReaderViewModel::class.java]
        compose.setContent { LexiconTheme { ReaderScreen(vm) { closed = true; vm.closeBook() } } }
    }

    @After fun cleanup() {
        compose.activity.viewModelStore.clear()
        Languages.current = previous
    }

    private fun epub(): File {
        val path = File(app.cacheDir, "english-opening.epub")
        ZipOutputStream(path.outputStream()).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>")
            put("book.opf", """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>English opening</dc:title></metadata>
                <manifest><item id="text" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="text"/></spine></package>""")
            put("chapter.xhtml", "<html><body><h1>Chapter One</h1>" +
                (1..30).joinToString("") { "<p>The rain was falling. She went slowly and looked up at the door.</p>" } + "</body></html>")
        }
        return path
    }

    @Test fun importedEnglishEpubOpensTheCompleteReaderWithAutomaticLevel() {
        var imported: Result<Long>? = null
        compose.runOnIdle { vm.importBook(Uri.fromFile(epub())) { imported = it } }
        awaitVm { imported != null }
        val id = imported!!.getOrThrow()
        compose.runOnIdle { vm.openBook(id) }
        awaitVm { vm.open.value?.let { !it.loading && it.chapter != null } == true }
        compose.waitForIdle()
        assertNull(vm.open.value?.error)
        compose.onNodeWithText("Открываю книгу…").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun brokenEpubShowsAnErrorAndCanReturnInsteadOfSpinningForever() {
        val id = runBlocking { LexiconDatabase.get(app).bookDao().insert(BookEntity(title = "Missing",
            author = "", fileName = "missing.epub", chapterCount = 1, chapterIndex = 0)) }
        compose.runOnIdle { vm.openBook(id) }
        awaitVm(10_000) { vm.openingError.value != null }
        compose.onNodeWithText("Не удалось открыть книгу", substring = true).assertExists()
        compose.onNodeWithText("Назад").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun loadingCanBeClosedBeforeAChapterHasBeenPrepared() {
        compose.onNodeWithText("Открываю книгу…").assertExists()
        compose.onNodeWithText("Назад").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }
}
