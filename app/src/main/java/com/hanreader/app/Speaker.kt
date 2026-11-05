package com.hanreader.app

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
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

    fun stop() { tts?.stop() }

    fun close() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
