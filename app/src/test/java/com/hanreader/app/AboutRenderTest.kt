package com.hanreader.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The «О приложении» section of the settings in the dark theme: build/screens/about.png. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersAboutSection() {
        AppearanceStore.update { it.copy(theme = ReaderTheme.NOIR) }
        compose.setContent {
            LexiconTheme {
                Column(Modifier.width(320.dp).background(MaterialTheme.colorScheme.surface).padding(24.dp)) { AboutSection() }
            }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File(File("build/screens").apply { mkdirs() }, "about.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        AppearanceStore.update { Appearance() }
    }
}
