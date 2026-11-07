package com.hanreader.app.reader

import android.app.Application
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.epub.TxtBook
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/** Reading aloud sentence by sentence; Android's speech engine is simulated. Own sample text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadAloudTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0)
    private val chapter = ChapterBuilder.build(
        TxtBook("第一章\n那天晚上一直在下雨。“你来了？”他问。\n门开了！老板笑了笑……".toByteArray(), "x"), 0, engine
    )

    @Test
    fun sentencesOfAChapter() {
        val list = Sentences.of(chapter)
        assertEquals(listOf("第一章", "那天晚上一直在下雨。", "“你来了？”", "他问。", "门开了！", "老板笑了笑……"), list.map { it.text })
        // Each sentence points back into its paragraph.
        list.forEach { s -> assertEquals(s.text, (chapter.blocks[s.block] as ReaderBlock.Text).text.substring(s.start, s.end)) }
        // Starting in the middle of a sentence reads that sentence again from its start.
        val second = list[2]
        assertEquals(2, Sentences.startAt(list, second.block, second.start + 1))
        assertEquals(list.size, Sentences.startAt(list, 99, 0))
    }

    @Test
    fun readsOnAndMovesToTheNextChapter() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        val r = ReadAloud(app)
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        var chapterEnded = 0
        r.onChapterEnd = { chapterEnded++ }

        val list = Sentences.of(chapter)
        // The simulated engine finishes every sentence at once: the whole chapter is read in order.
        r.start(chapter, list[1].block, 0)
        assertTrue(r.active && r.playing)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(list.drop(1).map { it.text }, tts.spokenTextList)
        assertEquals(1, chapterEnded)

        // Pausing right after a sentence starts: its late "done" is ignored, the reader stays on it.
        tts.clearLastSpokenText()
        r.start(chapter, list[2].block, list[2].start)
        r.pause()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(r.playing)
        assertEquals(list[2], r.current)
        assertEquals("“你来了？”", tts.lastSpokenText)

        // The engine reports the word being spoken: characters inside the paragraph.
        tts.utteranceProgressListener.onRangeStart(r.utteranceId, 1, 3, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals((list[2].start + 1) until (list[2].start + 3), r.word)

        // The speed is kept for the next time.
        r.changeRate(1.2f)
        assertEquals(1.2f, ReadAloud(app).rate, 0.001f)
        r.stop()
        assertFalse(r.active)
        assertEquals(null, r.current)
        r.close()
    }
}
