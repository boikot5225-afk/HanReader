package com.hanreader.app.reader

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.epub.EpubBook
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

/** Optional real uploaded EPUB through import, full reader, pages and scrolling; no API calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubSpacingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(60_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }
    @Test fun uploadedBookOpensWithItsSceneGapsInPagesAndScrolling() {
        val path = System.getenv("HANREADER_TEST_EPUB")
        assumeTrue(path != null && File(path).isFile)
        Languages.current = Languages.SPANISH
        AppearanceStore.load(app)
        AppearanceStore.update { it.copy(mode = ReadingMode.PAGES, gloss = GlossMode.OFF) }
        val source = File(path!!)
        val (index, block, expected) = EpubBook(source).use { b ->
            val c = b.chapters.first { it.path.endsWith("part0010.html") }
            val blocks = b.blocks(c.index)
            val i = blocks.indexOfFirst { it is EpubBlock.Text && it.text.length > 80 && it.style.spaceAfterEm >= 1.2f }
            assertTrue(i >= 0)
            Triple(c.index, i, (blocks[i] as EpubBlock.Text).text)
        }
        val vm = ViewModelProvider(compose.activity,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app))[ReaderViewModel::class.java]
        vm.contextAi.settings.update(vm.contextAi.settings.config.value.copy(enabled = false, automatic = false))
        compose.setContent { LexiconTheme { ReaderScreen(vm, vm::closeBook) } }
        var imported: Result<Long>? = null
        compose.runOnIdle { vm.importBook(Uri.fromFile(source)) { imported = it } }
        await { imported != null }
        compose.runOnIdle { vm.openBook(imported!!.getOrThrow()) }
        await { vm.open.value?.let { !it.loading && it.chapter != null } == true }
        compose.runOnIdle { vm.goToChapter(index, block = block) }
        await { vm.open.value?.let { !it.loading && it.chapter?.index == index } == true }
        compose.waitForIdle()
        fun check() {
            val chapter = vm.open.value!!.chapter!!
            val text = chapter.blocks[block] as ReaderBlock.Text
            assertEquals(expected, text.text)
            assertTrue(text.style.spaceAfterEm >= 1.2f)
            assertNull(vm.open.value!!.error)
        }
        compose.runOnIdle { check() }
        assertEquals(0, vm.contextAi.repository.usage.value.requests)
        compose.runOnIdle { AppearanceStore.update { it.copy(mode = ReadingMode.SCROLL) } }
        compose.waitForIdle()
        compose.runOnIdle { check() }
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File("build/screens").mkdirs()
        File("build/screens/uploaded-epub-spacing.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
