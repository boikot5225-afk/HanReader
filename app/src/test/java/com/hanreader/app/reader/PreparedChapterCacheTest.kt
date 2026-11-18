package com.hanreader.app.reader

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.*
import com.hanreader.app.epub.*
import com.hanreader.app.lang.Languages
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreparedChapterCacheTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val previous = Languages.current
    @After fun cleanup() { compose.activity.viewModelStore.clear(); Languages.current = previous }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }

    @Test fun cacheIsBoundedAndDoesNotReuseDifferentSegmentationInputs() {
        Languages.current = Languages.ENGLISH
        val engine = runBlocking { com.hanreader.app.lang.LanguageEngines.core(app).await() }
        val source = TxtBook("Fixture".toByteArray(), "Fixture")
        val key = PreparedChapterKey(source, 0, engine, emptySet(), emptySet(), ScriptMode.AS_BOOK)
        fun chapter(index: Int, chars: Int = 40) = ChapterContent(index, "Fixture", emptyList(), 0, chars)
        val cache = PreparedChapterCache(maxEntries = 2, maxChars = 100)
        val first = chapter(0)
        cache.put(key, first); cache.put(key.copy(index = 1), chapter(1))
        assertSame(first, cache[key]) // Access keeps chapter zero ahead of chapter one.
        cache.put(key.copy(index = 2), chapter(2))
        assertNull(cache[key.copy(index = 1)])
        assertSame(first, cache[key])
        assertNull(cache[key.copy(names = setOf("Alice"))])
        assertNull(cache[key.copy(splits = setOf("newspapers"))])
        assertNull(cache[key.copy(script = ScriptMode.TRADITIONAL)])
        assertNull(cache[key.copy(source = TxtBook("Other".toByteArray(), "Other"))])
        cache.put(key.copy(index = 3), chapter(3, 80))
        assertNull(cache[key]) // Total character budget also evicts old entries.
        cache.put(key.copy(index = 4), chapter(4, 101))
        assertNull(cache[key.copy(index = 4)]) // A single oversized chapter isn't cached.
        cache.clear()
        assertNull(cache[key.copy(index = 3)])
    }

    @Test fun returningToAChapterDoesNotParseItAgainButSplittingOrReopeningInvalidatesIt() {
        Languages.current = Languages.ENGLISH
        val reads = AtomicInteger()
        val text = TxtBook("She looked for a clue in the newspapers.".toByteArray(), "Fixture")
        val source = object : BookSource by text {
            override val chapters = listOf(EpubChapter(0, "one", "One"), EpubChapter(1, "two", "Two"))
            override fun blocks(chapterIndex: Int): List<EpubBlock> { reads.incrementAndGet(); return text.blocks(0) }
        }
        val id = runBlocking { LexiconDatabase.get(app).bookDao().insert(BookEntity(title = "Fixture", author = "",
            fileName = "cache-fixture.txt", chapterCount = 2, analyzedTokens = 1)) }
        app.getSharedPreferences("reader_settings", 0).edit().putInt("analysis_v_$id", BookAnalyzer.VERSION).commit()
        val vm = ReaderViewModel(app, bookOpener = { source })
        compose.activity.viewModelStore.put("reader", vm)
        vm.openBook(id)
        await { vm.open.value?.let { !it.loading && it.chapter?.index == 0 } == true }
        val first = vm.open.value!!.chapter!!
        assertEquals(1, reads.get())
        vm.goToChapter(1)
        await { vm.open.value?.let { !it.loading && it.chapter?.index == 1 } == true }
        assertEquals(2, reads.get())
        vm.goToChapter(0)
        await { vm.open.value?.let { !it.loading && it.chapter?.index == 0 } == true }
        assertSame(first, vm.open.value!!.chapter)
        assertEquals(2, reads.get())
        runBlocking { LexiconDatabase.get(app).userSplitDao().put(UserSplit("newspapers")) }
        vm.goToChapter(0)
        await { vm.open.value?.let { !it.loading && it.chapter !== first } == true }
        assertEquals(3, reads.get())
        vm.closeBook(); vm.openBook(id)
        await { vm.open.value?.let { !it.loading && it.chapter != null } == true }
        assertEquals(4, reads.get())
    }
}
