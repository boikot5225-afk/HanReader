package com.hanreader.app.reader

import android.app.Application
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.hanreader.app.Speaker
import com.hanreader.app.lang.Languages
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidVoiceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun chineseDialogOffersOnlyAndroidEvenWithOldServerSettings() {
        val previous = Languages.current
        app.getSharedPreferences("neural_voice", 0).edit().putBoolean("enabled", true)
            .putString("url", "https://old-voice.invalid").putString("token", "obsolete-test-token").commit()
        try {
            Languages.current = Languages.CHINESE
            compose.setContent { MaterialTheme { VoiceDialog {} } }
            compose.onNodeWithText("Читает синтезатор речи Android.", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Нейросетевой голос").assertDoesNotExist()
            compose.onNodeWithText("Адрес сервера").assertDoesNotExist()
        } finally {
            Languages.current = previous
            app.getSharedPreferences("neural_voice", 0).edit().clear().commit()
        }
    }

    @Test fun wordSpeechUsesAndroidForEveryLanguageDespiteLegacyServerPreference() {
        app.getSharedPreferences("neural_voice", 0).edit().putBoolean("enabled", true)
            .putString("url", "https://old-voice.invalid").commit()
        val speaker = Speaker(app)
        try {
            val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
            tts.onInitListener.onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            for ((language, text) in listOf(Languages.SPANISH to "pista", Languages.ENGLISH to "book", Languages.CHINESE to "你好")) {
                ShadowTextToSpeech.addLanguageAvailability(language.locale)
                speaker.speak(text, language.tag)
                assertEquals(text, tts.lastSpokenText)
            }
        } finally {
            speaker.close()
            app.getSharedPreferences("neural_voice", 0).edit().clear().commit()
        }
    }
}
