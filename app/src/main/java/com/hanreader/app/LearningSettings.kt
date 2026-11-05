package com.hanreader.app

import android.content.Context

enum class AutoSpeechMode(val title: String) {
    OFF("Выключена"),
    WORD("Только слово"),
    EXAMPLE("Только пример"),
    BOTH("Слово и пример")
}

data class LearningSettings(
    val language: String,
    val dailyNewWords: Int = 10,
    val autoSpeech: AutoSpeechMode = AutoSpeechMode.OFF,
    val speechRate: Float = 1.0f
)

class LearningSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "lexicon_learning_settings", Context.MODE_PRIVATE
    )

    companion object { val DAILY_LIMITS = listOf(5, 10, 20, 30, 50) }

    fun load(language: String): LearningSettings {
        val daily = prefs.getInt("daily_" + language, 10)
        val rawSpeech = prefs.getString("speech_" + language, AutoSpeechMode.OFF.name)
        val speech = AutoSpeechMode.entries.firstOrNull { it.name == rawSpeech }
            ?: AutoSpeechMode.OFF
        return LearningSettings(
            language = language,
            dailyNewWords = daily.takeIf { it in DAILY_LIMITS } ?: 10,
            autoSpeech = speech,
            speechRate = prefs.getFloat("rate_" + language, 1.0f).coerceIn(0.7f, 1.3f)
        )
    }

    fun save(settings: LearningSettings) {
        prefs.edit()
            .putInt("daily_" + settings.language, settings.dailyNewWords)
            .putString("speech_" + settings.language, settings.autoSpeech.name)
            .putFloat("rate_" + settings.language, settings.speechRate)
            .apply()
    }
}
