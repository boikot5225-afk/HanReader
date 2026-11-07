package com.hanreader.app.reader

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.hanreader.app.LexiconTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Statistics tiles and a day chart with sample numbers: build/screens/stats.png for a visual check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersStats() {
        val days = ReadingStats.lastDays(20261106, 14)
        val minutes = listOf(12, 0, 25, 18, 30, 0, 0, 8, 41, 22, 15, 0, 27, 19)
        compose.setContent {
            LexiconTheme {
                Column(Modifier.width(411.dp).background(MaterialTheme.colorScheme.background).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Сегодня", "19 мин", "3 820 знаков", Modifier.weight(1f))
                        StatTile("Серия", "4 дня", "рекорд 6", Modifier.weight(1f))
                        StatTile("Повторения", "35", "сегодня", Modifier.weight(1f))
                    }
                    ChartPanel("Чтение за 14 дней, минут", days, minutes, "Всего ${minutes.sum()} мин") { d, v -> "${ReadingStats.label(d)}: $v мин" }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(image))
        File(File("build/screens").apply { mkdirs() }, "stats.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
