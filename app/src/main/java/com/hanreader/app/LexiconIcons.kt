package com.hanreader.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Small line-icon set (24×24 viewport). Icon() tints them with the content color. */
object LexiconIcons {
    private fun line(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.9f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()

    private fun solid(name: String, data: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            addPath(pathData = addPathNodes(data), fill = SolidColor(Color.Black))
        }.build()

    val Close by lazy { line("close", "M18 6L6 18M6 6L18 18") }
    val Back by lazy { line("back", "M15 5L8 12L15 19") }
    val BookmarkLine by lazy { line("bookmark", "M6.5 4H17.5V20L12 16L6.5 20Z") }
    val BookmarkFilled by lazy { solid("bookmark_filled", "M6 3.5H18V21L12 16.6L6 21Z") }
    val ListBullets by lazy { line("list", "M9 6H20M9 12H20M9 18H20M4.5 6H5M4.5 12H5M4.5 18H5") }
    val Library by lazy { line("library", "M5 4V20M10 4V20M14.5 5L19 19.5M3 20H21") }
    val ChevronDown by lazy { line("chevron_down", "M6 9L12 15L18 9") }
    val Check by lazy { line("check", "M5 12.5L10 17.5L20 7") }
    val Plus by lazy { line("plus", "M12 5V19M5 12H19") }
    val Play by lazy {
        solid("play", "M8 5.5V18.5A1 1 0 0 0 9.5 19.36L20 12.86A1 1 0 0 0 20 11.14L9.5 4.64A1 1 0 0 0 8 5.5Z")
    }
    val Pen by lazy {
        line("pen", "M12 20H21", "M16.5 3.5A2.12 2.12 0 0 1 19.5 6.5L7 19L3 20L4 16Z")
    }
    val Sun by lazy {
        line(
            "today",
            "M12 8A4 4 0 1 0 12 16A4 4 0 1 0 12 8Z",
            "M12 2V4M12 20V22M4.9 4.9L6.3 6.3M17.7 17.7L19.1 19.1M2 12H4M20 12H22M4.9 19.1L6.3 17.7M17.7 6.3L19.1 4.9"
        )
    }
    val Book by lazy {
        line(
            "book",
            "M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5Z",
            "M4 19.5A2.5 2.5 0 0 0 6.5 22H20V17"
        )
    }
    val Download by lazy {
        line("import", "M21 15V19A2 2 0 0 1 19 21H5A2 2 0 0 1 3 19V15", "M7 10L12 15L17 10", "M12 15V3")
    }
    val Volume by lazy {
        line("speak", "M11 5L6 9H2V15H6L11 19Z", "M15.5 8.5A5 5 0 0 1 15.5 15.5", "M19 5A10 10 0 0 1 19 19")
    }
    val Search by lazy { line("search", "M11 4A7 7 0 1 0 11 18A7 7 0 1 0 11 4Z", "M20 20L16.5 16.5") }
    val Trash by lazy { line("delete", "M3 6H21", "M8 6V4H16V6", "M19 6L18 20H6L5 6") }
    val Copy by lazy { line("copy", "M9 9H20V20H9Z", "M5 15H4V4H15V5") }
    val Paste by lazy { line("paste", "M9 3H15V6H9Z", "M9 4.5H6V21H18V4.5H15") }
    val Wrong by lazy { line("wrong", "M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2Z", "M15 9L9 15M9 9L15 15") }
    val Settings by lazy {
        line(
            "settings",
            "M12 9A3 3 0 1 0 12 15A3 3 0 1 0 12 9Z",
            "M19.4 15A1.65 1.65 0 0 0 19.73 16.82L19.79 16.88A2 2 0 1 1 16.96 19.71L16.9 19.65A1.65 1.65 0 0 0 15.08 19.32A1.65 1.65 0 0 0 14.08 20.83V21A2 2 0 1 1 10.08 21V20.91A1.65 1.65 0 0 0 9 19.4A1.65 1.65 0 0 0 7.18 19.73L7.12 19.79A2 2 0 1 1 4.29 16.96L4.35 16.9A1.65 1.65 0 0 0 4.68 15.08A1.65 1.65 0 0 0 3.17 14.08H3A2 2 0 1 1 3 10.08H3.09A1.65 1.65 0 0 0 4.6 9A1.65 1.65 0 0 0 4.27 7.18L4.21 7.12A2 2 0 1 1 7.04 4.29L7.1 4.35A1.65 1.65 0 0 0 8.92 4.68H9A1.65 1.65 0 0 0 10 3.17V3A2 2 0 1 1 14 3V3.09A1.65 1.65 0 0 0 15 4.6A1.65 1.65 0 0 0 16.82 4.27L16.88 4.21A2 2 0 1 1 19.71 7.04L19.65 7.1A1.65 1.65 0 0 0 19.32 8.92V9A1.65 1.65 0 0 0 20.83 10H21A2 2 0 1 1 21 14H20.91A1.65 1.65 0 0 0 19.4 15Z"
        )
    }
}
