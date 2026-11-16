package com.hanreader.app.ai

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.lang.Languages
import com.hanreader.app.reader.ReaderScreen
import com.hanreader.app.reader.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

/** Real EPUB -> each real language engine -> complete reader -> mocked network -> Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiReaderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    private lateinit var vm: ReaderViewModel
    private lateinit var ai: ContextAi
    private val calls = AtomicInteger()
    private val language get() = when {
        testName.methodName.startsWith("english") -> Languages.ENGLISH
        testName.methodName.startsWith("chinese") -> Languages.CHINESE
        else -> Languages.SPANISH
    }
    private val sourceText get() = when (language) {
        Languages.ENGLISH -> "She sat on the bank by the river."
        Languages.CHINESE -> "他在银行门口等朋友。"
        else -> "Buscaba una pista en los periódicos."
    }
    private val target get() = when (language) {
        Languages.ENGLISH -> "bank"
        Languages.CHINESE -> "银行"
        else -> "pista"
    }
    private val expected get() = when (language) {
        Languages.ENGLISH -> "берег"
        Languages.CHINESE -> "банк"
        else -> "улика"
    }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }
    @Before fun setup() {
        Languages.current = language
        AppearanceStore.load(app)
        AppearanceStore.update { it.copy(gloss = GlossMode.UNKNOWN, glossFade = false) }
        app.getSharedPreferences("context_ai", 0).edit().clear().commit()
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val settings = AiSettings(app, AiKeyVault(app) { aes })
        settings.vault.save("mock-key-not-a-real-account")
        settings.refresh()
        settings.update(settings.config.value.copy(enabled = true, consent = true, automatic = true))
        ai = ContextAi(app, settings, AiClient { _, _, payload ->
            calls.incrementAndGet()
            assertEquals(language.tag, payload.getString("language"))
            val input = payload.getJSONArray("terms")
            val results = JSONArray()
            for (i in 0 until input.length()) {
                val t = input.getJSONObject(i)
                val correction = t.getString("original") == target
                results.put(JSONObject().put("term_id", t.getString("term_id")).put("original", t.getString("original"))
                    .put("current_translation", t.getString("current_translation")).put("needs_correction", correction)
                    .put("suggested_translation", if (correction) expected else JSONObject.NULL)
                    .put("explanation", JSONObject.NULL).put("confidence", "high"))
            }
            AiReply(JSONObject().put("results", results).toString(), 80, true)
        })
        vm = ViewModelProvider(compose.activity, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(app, ai) as T
        })[ReaderViewModel::class.java]
        compose.setContent { LexiconTheme { ReaderScreen(vm, vm::closeBook) } }
    }
    @After fun cleanup() {
        compose.activity.viewModelStore.clear()
        ai.settings.vault.delete()
        Languages.current = previous
    }
    private fun epub(): File {
        val path = File(app.cacheDir, "ai-reader.epub")
        ZipOutputStream(path.outputStream()).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>")
            put("book.opf", """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Prueba</dc:title><dc:language>${language.tag}</dc:language></metadata>
                <manifest><item id="text" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="text"/></spine></package>""")
            put("chapter.xhtml", "<html><body><p>$sourceText</p></body></html>")
        }
        return path
    }
    @Test fun readingAutomaticallyChecksTheVisibleParagraphAndReopenWithLargerFontUsesCache() = readingAndReopeningUseCache()
    @Test fun englishReadingAutomaticallyChecksAndReusesCache() = readingAndReopeningUseCache()
    @Test fun chineseReadingAutomaticallyChecksAndReusesCache() = readingAndReopeningUseCache()

    private fun readingAndReopeningUseCache() {
        var imported: Result<Long>? = null
        compose.runOnIdle { vm.importBook(Uri.fromFile(epub())) { imported = it } }
        await { imported != null }
        val id = imported!!.getOrThrow()
        compose.runOnIdle { vm.openBook(id) }
        await { calls.get() > 0 && ai.repository.message.value != null }
        val rows = runBlocking { ai.repository.observe(id).first() }
        assertTrue("${ai.repository.message.value}; rows=$rows", rows.any { it.state == "accepted" && it.translation == expected })
        assertEquals(1, calls.get())
        compose.runOnIdle { AppearanceStore.update { it.copy(fontSize = it.fontSize + 2) }; vm.closeBook() }
        await { vm.open.value == null }
        compose.runOnIdle { vm.openBook(id) }
        await { vm.open.value?.let { !it.loading && it.chapter != null } == true }
        // Let the reading debounce run again; persisted checks must absorb it.
        val until = System.nanoTime() + 1_600_000_000L
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); System.nanoTime() >= until }
        assertEquals(1, calls.get())
        assertTrue(runBlocking { ai.repository.observe(id).first() }.any { it.translation == expected })
    }
}
