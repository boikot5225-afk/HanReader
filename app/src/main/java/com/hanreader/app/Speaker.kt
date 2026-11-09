package com.hanreader.app

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private val neural = NeuralVoice.get(context)
    private val audio = AudioPlayer()
    private var request = 0
    var ready by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var rate: Float = 1.0f

    init { tts = TextToSpeech(context.applicationContext, this) }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) message = "Озвучка Android недоступна"
    }

    private fun prepare(tag: String): TextToSpeech? {
        val engine = tts
        if (!ready || engine == null) {
            message = "Озвучка ещё не готова"
            return null
        }
        val languageResult = engine.setLanguage(Locale.forLanguageTag(tag))
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            message = "Нет голоса для этого языка. Установи его в настройках Android"
            return null
        }
        engine.setSpeechRate(rate.coerceIn(0.7f, 1.3f))
        message = ""
        return engine
    }

    fun speak(text: String, tag: String) {
        // Chinese in the server's voice when it is on; the phone's voice if the server fails.
        if (neural.available && tag.startsWith("zh")) {
            tts?.stop()
            audio.stop()
            val id = ++request
            message = ""
            neural.fetch(text, 1.0f) { result ->
                if (id != request) return@fetch
                result.fold(
                    onSuccess = { audio.play(it, onDone = {}, onError = { speakWithPhone(text, tag) }) },
                    onFailure = { speakWithPhone(text, tag) }
                )
            }
            return
        }
        speakWithPhone(text, tag)
    }

    private fun speakWithPhone(text: String, tag: String) {
        val engine = prepare(tag) ?: return
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lexicon-manual") ==
            TextToSpeech.ERROR
        ) {
            message = "Ошибка воспроизведения"
        }
    }

    fun speakAutomatically(
        word: String, example: String, tag: String, mode: AutoSpeechMode
    ) {
        if (mode == AutoSpeechMode.OFF) return
        val engine = prepare(tag) ?: return
        val items = when (mode) {
            AutoSpeechMode.OFF -> emptyList()
            AutoSpeechMode.WORD -> listOf(word)
            AutoSpeechMode.EXAMPLE -> listOf(example)
            AutoSpeechMode.BOTH -> listOf(word, example)
        }.filter { it.isNotBlank() }
        items.forEachIndexed { index, item ->
            if (engine.speak(
                item,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, "lexicon-auto-" + index
            ) == TextToSpeech.ERROR) message = "Ошибка автоозвучки"
        }
    }

    fun stop() { request++; tts?.stop(); audio.stop() }

    fun close() {
        request++
        audio.stop()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
