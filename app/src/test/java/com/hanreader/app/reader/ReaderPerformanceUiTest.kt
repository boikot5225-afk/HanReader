package com.hanreader.app.reader

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.LanguageInfo
import com.hanreader.app.lang.Languages
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

/** Synthetic books only: timings are diagnostics, never device-performance promises. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPerformanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }
    private fun await(stage: String, condition: () -> Boolean) {
        val start = System.nanoTime()
        compose.waitUntil(60_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
        println("READER_PERF ${Languages.current.tag} $stage ${(System.nanoTime() - start) / 1_000_000}ms")
    }
    private fun exercise(language: LanguageInfo, sentence: String) {
        Languages.current = language
        AppearanceStore.load(app)
        AppearanceStore.update { it.copy(mode = ReadingMode.PAGES, gloss = GlossMode.UNKNOWN) }
        val file = File(app.cacheDir, "performance-${language.tag}.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>")
            put("book.opf", """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Performance fixture</dc:title></metadata><manifest>""" +
                (0..11).joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" } +
                "</manifest><spine>" + (0..11).joinToString("") { "<itemref idref=\"c$it\"/>" } + "</spine></package>")
            for (i in 0..11) put("c$i.xhtml", "<html><body>" + (1..120).joinToString("") {
                "<p>$sentence $sentence</p>"
            } + "</body></html>")
        }
        val vm = ReaderViewModel(app)
        compose.activity.viewModelStore.put("reader", vm)
        vm.contextAi.settings.update(vm.contextAi.settings.config.value.copy(enabled = false, automatic = false))
        compose.setContent { LexiconTheme { ReaderScreen(vm, vm::closeBook) } }
        var imported: Result<Long>? = null
        compose.runOnIdle { vm.importBook(Uri.fromFile(file)) { imported = it } }
        await("import") { imported != null }
        compose.runOnIdle { vm.openBook(imported!!.getOrThrow()) }
        await("first-chapter") { vm.open.value?.let { !it.loading && it.chapter?.index == 0 } == true }
        await("first-pages") { compose.onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        await("analysis") { vm.analysis.value.isEmpty() && vm.open.value?.entity?.analyzedTokens != 0 }
        await("chapter-settled") { vm.open.value?.loading == false }
        val first = vm.open.value!!.chapter
        compose.runOnIdle { vm.goToChapter(1) }
        await("next-chapter") { vm.open.value?.let { !it.loading && it.chapter?.index == 1 } == true }
        compose.runOnIdle { vm.goToChapter(0) }
        await("cached-return") { vm.open.value?.let { !it.loading && it.chapter?.index == 0 } == true }
        assertSame("Return rebuilt the chapter", first, vm.open.value!!.chapter)
        await("return-pages") { compose.onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertNull(vm.open.value!!.error)
        assertEquals(0, vm.contextAi.repository.usage.value.requests)
        file.delete()
    }
    @Test fun spanishLargeBook() = exercise(Languages.SPANISH, "Buscaba una pista en los periódicos. La noche era tranquila y volvió a casa.")
    @Test fun englishLargeBook() = exercise(Languages.ENGLISH, "She looked for a clue in the newspapers. The night was quiet and she went home.")
    @Test fun chineseLargeBook() = exercise(Languages.CHINESE, "他在报纸里寻找线索。夜里很安静，他慢慢走回家。")
}
