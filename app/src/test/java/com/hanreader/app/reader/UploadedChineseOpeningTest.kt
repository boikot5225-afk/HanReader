package com.hanreader.app.reader

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
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UploadedChineseOpeningTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }
    private fun await(label: String, condition: () -> Boolean) {
        val started = System.nanoTime()
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
        println("CHINESE_OPEN $label ${(System.nanoTime()-started)/1_000_000}ms")
    }
    @Test fun uploadedChineseCoverAndFirstTextChapterFinishPagination() {
        val path = System.getenv("HANREADER_TEST_EPUB")
        assumeTrue(path != null && File(path).isFile)
        Languages.current = Languages.CHINESE
        AppearanceStore.load(app)
        AppearanceStore.update { it.copy(mode = ReadingMode.PAGES, gloss = GlossMode.UNKNOWN) }
        val vm = ViewModelProvider(compose.activity, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[ReaderViewModel::class.java]
        vm.contextAi.settings.update(vm.contextAi.settings.config.value.copy(enabled = false, automatic = false))
        compose.setContent { LexiconTheme { ReaderScreen(vm, vm::closeBook) } }
        var imported: Result<Long>? = null
        compose.runOnIdle { vm.importBook(Uri.fromFile(File(path!!))) { imported = it } }
        await("import") { imported != null }
        compose.runOnIdle { vm.openBook(imported!!.getOrThrow()) }
        await("chapter") { vm.open.value?.let { !it.loading && it.chapter != null } == true }
        println("CHINESE_OPEN blocks ${vm.open.value!!.chapter!!.blocks.map { it.javaClass.simpleName }}")
        await("cover pagination") { compose.onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        await("background analysis") { vm.open.value?.let { !it.loading && it.entity.analyzedTokens > 0 } == true }
        await("cover after analysis") { compose.onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { vm.goToChapter(3) }
        await("text chapter") { vm.open.value?.let { !it.loading && it.chapter?.index == 3 } == true }
        await("text pagination") { compose.onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertNull(vm.open.value!!.error)
        assertEquals(0, vm.contextAi.repository.usage.value.requests)
    }
}
