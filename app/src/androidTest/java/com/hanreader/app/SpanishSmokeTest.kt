package com.hanreader.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hanreader.app.lang.Languages
import com.hanreader.app.reader.ReaderViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Spanish mode on a real Android system: the language switched to Spanish, a Spanish text
 * added (own sample), opened, a page turned. The Spanish dictionary loads from app storage.
 * The language is switched back to Chinese afterwards for the other tests.
 */
@RunWith(AndroidJUnit4::class)
class SpanishSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val text = """Capítulo 1

La lluvia había empezado poco después de medianoche, y a las seis de la mañana las calles del puerto seguían convertidas en ríos estrechos. Marta cerró la ventana de la cocina y se quedó mirando el reloj de la pared.

—¿Vas a salir con este tiempo? —preguntó su hermano desde el pasillo.

—No me queda otro remedio. El barco llega a las ocho y alguien tiene que firmar los papeles.

Capítulo 2

El edificio de la aduana era una construcción antigua, de piedra gris, con ventanas altísimas. Dentro, el señor Ortega ya la esperaba detrás del mostrador, con las gafas en la punta de la nariz."""

    private fun setLanguage(app: Context, tag: String) {
        app.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("studyLanguage", tag).commit()
        Languages.init(app)
    }

    @Test
    fun readASpanishText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        setLanguage(app, Languages.SPANISH.tag)
        try {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            val file = File(app.cacheDir, "La lluvia.txt").apply { writeText(text) }
            val done = CountDownLatch(1)
            var result: Result<Long>? = null
            instrumentation.runOnMainSync { ReaderViewModel(app).importBook(Uri.fromFile(file)) { result = it; done.countDown() } }
            assertTrue("import finished", done.await(60, TimeUnit.SECONDS))
            assertTrue("imported: $result", result!!.isSuccess)
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("La lluvia")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasText("La lluvia"))[0].performClick()
            // The reader: the running header with the chapter title (the dictionary has loaded).
            compose.waitUntil(60_000) { compose.onAllNodes(hasText("Capítulo 1")).fetchSemanticsNodes().isNotEmpty() }
            compose.onRoot().performTouchInput { swipeLeft(startX = width * 0.8f, endX = width * 0.2f) }
            compose.waitForIdle()
            scenario.close()
        } finally {
            setLanguage(app, Languages.CHINESE.tag)
        }
    }
}
