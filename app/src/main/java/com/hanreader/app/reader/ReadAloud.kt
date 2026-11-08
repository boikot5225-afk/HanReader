package com.hanreader.app.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hanreader.app.zh.TokenKind

/** A sentence to read aloud: characters [start, end) of paragraph [block] in chapter [chapter]. */
data class Spoken(val chapter: Int, val block: Int, val start: Int, val end: Int, val text: String)

object Sentences {
    private val END = com.hanreader.app.lang.Languages.current.sentenceEnd.replace("\n", "")
    private const val CLOSING = "”’」』\")）"

    /** The chapter's sentences in reading order; headings count as one sentence, empty ones are skipped. */
    fun of(chapter: ChapterContent): List<Spoken> {
        val out = ArrayList<Spoken>()
        for ((bi, b) in chapter.blocks.withIndex()) {
            if (b !is ReaderBlock.Text) continue
            val text = b.text
            var s = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c in END || c == '\n') {
                    var e = i + 1
                    while (e < text.length && (text[e] in END || text[e] in CLOSING)) e++
                    add(out, chapter.index, bi, text, s, e)
                    s = e
                    i = e
                } else i++
            }
            add(out, chapter.index, bi, text, s, text.length)
        }
        return out
    }

    private fun add(out: MutableList<Spoken>, chapter: Int, block: Int, text: String, from: Int, to: Int) {
        var s = from
        var e = to
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        // Something to say: a letter or a character, not punctuation alone.
        if (s < e && (s until e).any { text[it].isLetterOrDigit() }) out += Spoken(chapter, block, s, e, text.substring(s, e))
    }

    /** The sentence to start from at character [char] of paragraph [block]: the one holding it, or the next. */
    fun startAt(list: List<Spoken>, block: Int, char: Int): Int {
        val i = list.indexOfFirst { it.block > block || (it.block == block && it.end > char) }
        return if (i < 0) list.size else i
    }
}

/**
 * Reads a chapter aloud with Android's speech synthesis, one sentence at a time, so the screen
 * always knows which sentence (and, with engines that report it, which word) is being spoken.
 * A sentence finished moves on to the next; the chapter's end asks the screen for the next one.
 */
@Stable
class ReadAloud(context: Context) : TextToSpeech.OnInitListener {
    private val prefs = context.applicationContext.getSharedPreferences("read_aloud", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingSpeak = false

    /** Reading is on (the player is shown), playing or paused. */
    var active by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    /** The sentence being read. */
    var current by mutableStateOf<Spoken?>(null)
        private set
    /** Characters of the word being spoken inside [current]'s paragraph, if the engine reports it. */
    var word by mutableStateOf<IntRange?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var rate by mutableFloatStateOf(prefs.getFloat("rate", 1.0f))
        private set

    /** Called when the chapter's last sentence is done: load the next chapter and call [start] again. */
    var onChapterEnd: (() -> Unit)? = null

    private var sentences: List<Spoken> = emptyList()
    private var index = 0
    // Callbacks of an utterance that was replaced (pause, skip) are ignored.
    private var generation = 0

    val chapterIndex: Int? get() = sentences.firstOrNull()?.chapter

    override fun onInit(status: Int) {
        main.post {
            ready = status == TextToSpeech.SUCCESS
            if (!ready) { message = "Озвучка Android недоступна"; stop(); return@post }
            tts?.setOnUtteranceProgressListener(listener)
            if (pendingSpeak) { pendingSpeak = false; speakCurrent() }
        }
    }

    /** Starts reading [chapter] at character [char] of paragraph [block]. */
    fun start(chapter: ChapterContent, block: Int, char: Int) {
        sentences = Sentences.of(chapter)
        index = Sentences.startAt(sentences, block, char)
        active = true
        playing = true
        message = ""
        speakCurrent()
    }

    fun pause() {
        playing = false
        generation++
        tts?.stop()
        word = null
    }

    fun resume() {
        if (!active) return
        playing = true
        speakCurrent()
    }

    fun next() = move(1)
    fun previous() = move(-1)

    private fun move(by: Int) {
        if (!active || sentences.isEmpty()) return
        index = (index + by).coerceIn(0, sentences.size - 1)
        if (playing) speakCurrent() else { current = sentences[index]; word = null }
    }

    fun stop() {
        active = false
        playing = false
        current = null
        word = null
        generation++
        tts?.stop()
    }

    /** Speech rate, 0.5..2 (1 = the engine's normal speed); takes effect from the next sentence. */
    fun changeRate(value: Float) {
        rate = value.coerceIn(0.5f, 2f)
        prefs.edit().putFloat("rate", rate).apply()
        if (playing) speakCurrent()
    }

    fun close() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun speakCurrent() {
        val s = sentences.getOrNull(index)
        if (s == null) {
            current = null
            word = null
            onChapterEnd?.invoke() ?: stop()
            return
        }
        current = s
        word = null
        val engine = tts ?: return
        if (!ready) { pendingSpeak = true; return }
        val lang = engine.setLanguage(com.hanreader.app.lang.Languages.current.locale)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            message = com.hanreader.app.lang.Languages.current.voiceMissing
            pause()
            return
        }
        engine.setSpeechRate(rate)
        generation++
        if (engine.speak(s.text, TextToSpeech.QUEUE_FLUSH, null, "ra-$generation-$index") == TextToSpeech.ERROR) {
            message = "Ошибка озвучки"
            pause()
        }
    }

    /** The id of the utterance now expected ("ra-<generation>-<index>"); older ids are ignored. */
    internal val utteranceId: String get() = "ra-$generation-$index"

    private fun isCurrent(id: String?): Boolean = id == utteranceId

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            main.post {
                if (!playing || !isCurrent(utteranceId)) return@post
                index++
                speakCurrent()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { if (isCurrent(utteranceId)) { message = "Ошибка озвучки"; pause() } }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            main.post {
                val s = current ?: return@post
                if (isCurrent(utteranceId)) word = (s.start + start) until (s.start + end)
            }
        }
    }

    companion object {
        val RATES = listOf(0.6f, 0.8f, 1.0f, 1.2f, 1.5f)
    }
}

/** Tokens of [block] covered by characters [range]: for drawing the word being spoken. */
internal fun tokensOf(block: ReaderBlock.Text, range: IntRange?): IntRange? {
    range ?: return null
    val r = Highlights.range(block, range.first, range.last + 1) ?: return null
    // Only Han words: punctuation inside the range is not "spoken".
    return if ((r).any { block.tokens[it].kind == TokenKind.HAN }) r else null
}
