package com.hanreader.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Warm "paper" palette: cream ground, graphite ink, one teal accent
 * for success and amber for new words. Errors are a muted brick red.
 */
private val LightLexicon = lightColorScheme(
    primary = Color(0xFF1F6F66),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6EFEA),
    onPrimaryContainer = Color(0xFF0E3B36),
    secondary = Color(0xFFC9822B),
    onSecondary = Color(0xFF1F1D1A),
    secondaryContainer = Color(0xFFF6E3C4),
    onSecondaryContainer = Color(0xFF6E470C),
    background = Color(0xFFF6F1E7),
    onBackground = Color(0xFF1F1D1A),
    surface = Color(0xFFFFFCF6),
    onSurface = Color(0xFF1F1D1A),
    surfaceVariant = Color(0xFFEDE5D6),
    onSurfaceVariant = Color(0xFF625C53),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF7EF),
    surfaceContainer = Color(0xFFEDE5D6),
    surfaceContainerHigh = Color(0xFFE6DCC9),
    surfaceContainerHighest = Color(0xFFDCD3C2),
    inverseSurface = Color(0xFF1F1D1A),
    inverseOnSurface = Color(0xFFF6F1E7),
    outline = Color(0xFFD9CFBC),
    outlineVariant = Color(0xFFE8E0D0),
    error = Color(0xFFA23B2A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF7E1DA),
    onErrorContainer = Color(0xFF8E2F20)
)

private val DarkLexicon = darkColorScheme(
    primary = Color(0xFF7FD1C4),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF1C4A44),
    onPrimaryContainer = Color(0xFFD6EFEA),
    secondary = Color(0xFFF2B66B),
    onSecondary = Color(0xFF3D2700),
    secondaryContainer = Color(0xFF4A3415),
    onSecondaryContainer = Color(0xFFF6E3C4),
    background = Color(0xFF161412),
    onBackground = Color(0xFFEDE6DA),
    surface = Color(0xFF211E1A),
    onSurface = Color(0xFFEDE6DA),
    surfaceVariant = Color(0xFF2C2823),
    onSurfaceVariant = Color(0xFFB3AA9C),
    surfaceContainerLowest = Color(0xFF110F0D),
    surfaceContainerLow = Color(0xFF1B1916),
    surfaceContainer = Color(0xFF2C2823),
    surfaceContainerHigh = Color(0xFF3A352E),
    surfaceContainerHighest = Color(0xFF46403A),
    // The app uses inverseSurface only for its dark "hero" blocks.
    inverseSurface = Color(0xFF2E2A25),
    inverseOnSurface = Color(0xFFF6F1E7),
    outline = Color(0xFF4A443C),
    outlineVariant = Color(0xFF34302A),
    error = Color(0xFFFFB4A6),
    onError = Color(0xFF5C1F14),
    errorContainer = Color(0xFF5C1F14),
    onErrorContainer = Color(0xFFF7E1DA)
)

/** Accents that stay readable on the dark hero block in both themes. */
val HeroReviewColor = Color(0xFF7FD1C4)
val HeroNewColor = Color(0xFFF2B66B)

/** The theme in use and the reader's chosen Chinese font, for every screen. */
val LocalThemeSpec = staticCompositionLocalOf { ReaderTheme.PAPER.spec }
val LocalHanFont = staticCompositionLocalOf<FontFamily> { FontFamily.Serif }

/** Studied words and examples use the Chinese font chosen in «Оформление». */
val WordFont: FontFamily
    @Composable @ReadOnlyComposable get() = LocalHanFont.current

private fun typography(spec: ThemeSpec, han: FontFamily): Typography {
    val base = Typography()
    fun body(t: TextStyle) = t.copy(fontFamily = spec.body)
    fun display(t: TextStyle) = t.copy(fontFamily = han, fontWeight = FontWeight.SemiBold)
    fun title(t: TextStyle) = t.copy(fontFamily = spec.display, fontWeight = if (spec.display == spec.body) FontWeight.SemiBold else FontWeight.Medium)
    return Typography(
        displayLarge = display(base.displayLarge), displayMedium = display(base.displayMedium), displaySmall = display(base.displaySmall),
        headlineLarge = display(base.headlineLarge), headlineMedium = display(base.headlineMedium), headlineSmall = display(base.headlineSmall),
        titleLarge = title(base.titleLarge), titleMedium = title(base.titleMedium), titleSmall = title(base.titleSmall),
        bodyLarge = body(base.bodyLarge), bodyMedium = body(base.bodyMedium), bodySmall = body(base.bodySmall),
        labelLarge = body(base.labelLarge), labelMedium = body(base.labelMedium), labelSmall = body(base.labelSmall)
    )
}

private val LexiconShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LexiconTheme(content: @Composable () -> Unit) {
    val appearance by AppearanceStore.appearance.collectAsState()
    val spec = appearance.theme.resolve(isSystemInDarkTheme()).spec
    val han = appearance.hanFontFor(spec).family
    CompositionLocalProvider(LocalThemeSpec provides spec, LocalHanFont provides han) {
        MaterialTheme(
            colorScheme = spec.colorScheme(),
            typography = typography(spec, han),
            shapes = if (spec.corner == 0.dp) SharpShapes else LexiconShapes,
            content = content
        )
    }
}

private val SharpShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(6.dp)
)
