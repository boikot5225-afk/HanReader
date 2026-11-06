package com.hanreader.app

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ---------------------------------------------------------------- fonts

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: FontWeight): FontFamily = FontFamily(
    weights.map { w -> Font(res, w, FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight))) }
)

object AppFonts {
    val NotoSerifSc: FontFamily = variable(R.font.noto_serif_sc, FontWeight.Normal, FontWeight.SemiBold)
    val NotoSansSc: FontFamily = variable(R.font.noto_sans_sc, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)
    val WenKai: FontFamily = FontFamily(Font(R.font.lxgw_wenkai, FontWeight.Normal))
    val Fangsong: FontFamily = FontFamily(Font(R.font.zhuque_fangsong, FontWeight.Normal))
    val Oswald: FontFamily = variable(R.font.oswald, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)
    val PlexMono: FontFamily = FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium)
    )
    @OptIn(ExperimentalTextApi::class)
    val Cormorant: FontFamily = FontFamily(
        Font(R.font.cormorant, FontWeight.Medium, FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.cormorant, FontWeight.SemiBold, FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.cormorant_italic, FontWeight.Medium, FontStyle.Italic, variationSettings = FontVariation.Settings(FontVariation.weight(500)))
    )
    val Inter: FontFamily = variable(R.font.inter, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)
}

/** The four classic styles of Chinese type, plus the phone's own font. */
enum class HanFont(val title: String, val style: String, val sample: String) {
    SERIF("Noto Serif SC", "宋体 · антиква", "雨夜的灯"),
    SANS("Noto Sans SC", "黑体 · гротеск", "雨夜的灯"),
    KAI("LXGW WenKai", "楷体 · кай", "雨夜的灯"),
    FANGSONG("Zhuque Fangsong", "仿宋 · фансун", "雨夜的灯"),
    SYSTEM("Системный", "как в телефоне", "雨夜的灯");

    val family: FontFamily
        get() = when (this) {
            SERIF -> AppFonts.NotoSerifSc
            SANS -> AppFonts.NotoSansSc
            KAI -> AppFonts.WenKai
            FANGSONG -> AppFonts.Fangsong
            SYSTEM -> FontFamily.Default
        }
}

// ---------------------------------------------------------------- themes

/** Everything a theme decides: colours, Latin/Cyrillic type, corner radius. */
data class ThemeSpec(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val accent: Color,
    /** Accent used as text (lighter on dark themes, for contrast). */
    val accentText: Color,
    val onAccent: Color,
    val selection: Color,
    val pinyin: Color,
    val saved: Color,
    /** Underline of words not known yet (red) and of words recommended for the reader's level (blue). */
    val unknownLine: Color,
    val recommendedLine: Color,
    /** Dark "hero" block used by the study screens and the main buttons. */
    val hero: Color,
    val onHero: Color,
    val display: FontFamily,
    val body: FontFamily,
    val pinyinFont: FontFamily,
    val pinyinItalic: Boolean,
    /** Pinyin size correction for fonts with a small x-height (Cormorant). */
    val pinyinBoost: Float = 1f,
    val corner: Dp,
    val defaultHanFont: HanFont
)

enum class ReaderTheme(val title: String, val note: String) {
    NOIR("Нуар", "чёрно-белая, один красный акцент"),
    NEON("Неонуар", "ночной город, неон"),
    CLASSIC("Классика", "бумага, чернила, бордо"),
    PAPER("Бумага", "светлая, для дня"),
    AUTO("Авто", "днём «Бумага», ночью «Нуар»");

    /** The concrete theme: AUTO follows the system day/night setting. */
    fun resolve(systemDark: Boolean): ReaderTheme = if (this == AUTO) (if (systemDark) NOIR else PAPER) else this

    val spec: ThemeSpec
        get() = when (this) {
            NOIR, AUTO -> NoirSpec
            NEON -> NeonSpec
            CLASSIC -> ClassicSpec
            PAPER -> PaperSpec
        }
}

private val NoirSpec = ThemeSpec(
    dark = true,
    background = Color(0xFF0D0D0D), surface = Color(0xFF161616), surfaceHigh = Color(0xFF222222),
    text = Color(0xFFEDE9E2), muted = Color(0xFF9A968F), border = Color(0xFF333333),
    accent = Color(0xFFE0574A), accentText = Color(0xFFE0574A), onAccent = Color(0xFF0D0D0D),
    selection = Color(0xFF3B1513), pinyin = Color(0xFF9A968F), saved = Color(0xFFEDE9E2),
    unknownLine = Color(0xFFE0574A), recommendedLine = Color(0xFF6EA8FF),
    hero = Color(0xFFEDE9E2), onHero = Color(0xFF0D0D0D),
    display = AppFonts.Oswald, body = AppFonts.Inter, pinyinFont = AppFonts.Inter, pinyinItalic = false,
    corner = 0.dp, defaultHanFont = HanFont.SERIF
)

private val NeonSpec = ThemeSpec(
    dark = true,
    background = Color(0xFF0A0C14), surface = Color(0xFF11142A), surfaceHigh = Color(0xFF1A1F3A),
    text = Color(0xFFE7E4F2), muted = Color(0xFF8F93AD), border = Color(0xFF2E3355),
    accent = Color(0xFFFF4F9E), accentText = Color(0xFFFF6FB0), onAccent = Color(0xFF14050C),
    selection = Color(0xFF36182D), pinyin = Color(0xFF4FD8E6), saved = Color(0xFFF2B66B),
    unknownLine = Color(0xFFFF5C6C), recommendedLine = Color(0xFF6EA8FF),
    hero = Color(0xFFFF4F9E), onHero = Color(0xFF14050C),
    display = AppFonts.PlexMono, body = AppFonts.Inter, pinyinFont = AppFonts.PlexMono, pinyinItalic = false,
    corner = 14.dp, defaultHanFont = HanFont.SERIF
)

private val ClassicSpec = ThemeSpec(
    dark = false,
    background = Color(0xFFF3EDE1), surface = Color(0xFFFBF7EF), surfaceHigh = Color(0xFFE9E0CF),
    text = Color(0xFF1D1B18), muted = Color(0xFF6B6459), border = Color(0xFFCFC3AE),
    accent = Color(0xFF7A2E2A), accentText = Color(0xFF7A2E2A), onAccent = Color(0xFFFBF7EF),
    selection = Color(0xFFE6D3C2), pinyin = Color(0xFF6B6459), saved = Color(0xFF2E6B5E),
    unknownLine = Color(0xFFB3261E), recommendedLine = Color(0xFF1E5BB8),
    hero = Color(0xFF7A2E2A), onHero = Color(0xFFFBF7EF),
    display = AppFonts.Cormorant, body = AppFonts.Cormorant, pinyinFont = AppFonts.Cormorant, pinyinItalic = true, pinyinBoost = 1.15f,
    corner = 0.dp, defaultHanFont = HanFont.SERIF
)

private val PaperSpec = ThemeSpec(
    dark = false,
    background = Color(0xFFF7F5F0), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFEAE6DD),
    text = Color(0xFF1F1E1C), muted = Color(0xFF5F5B54), border = Color(0xFFDAD5CB),
    accent = Color(0xFF1F6F66), accentText = Color(0xFF1F6F66), onAccent = Color(0xFFFFFFFF),
    selection = Color(0xFFD6EFEA), pinyin = Color(0xFF5F5B54), saved = Color(0xFF1F6F66),
    unknownLine = Color(0xFFC62828), recommendedLine = Color(0xFF1E5BB8),
    hero = Color(0xFF1F1E1C), onHero = Color(0xFFF7F5F0),
    display = AppFonts.Inter, body = AppFonts.Inter, pinyinFont = AppFonts.Inter, pinyinItalic = false,
    corner = 16.dp, defaultHanFont = HanFont.SANS
)

/** Material colours for the whole app, from the theme. */
fun ThemeSpec.colorScheme(): ColorScheme {
    val error = if (dark) Color(0xFFFFB4A6) else Color(0xFFA23B2A)
    return if (dark) darkColorScheme(
        primary = accentText, onPrimary = onAccent, primaryContainer = selection, onPrimaryContainer = text,
        secondary = accentText, onSecondary = onAccent, secondaryContainer = selection, onSecondaryContainer = text,
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = surfaceHigh, onSurfaceVariant = muted,
        surfaceContainerLowest = background, surfaceContainerLow = surface, surfaceContainer = surfaceHigh,
        surfaceContainerHigh = surfaceHigh, surfaceContainerHighest = border,
        inverseSurface = hero, inverseOnSurface = onHero, outline = border, outlineVariant = border,
        error = error, onError = background, errorContainer = selection, onErrorContainer = text
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = selection, onPrimaryContainer = text,
        secondary = accent, onSecondary = onAccent, secondaryContainer = selection, onSecondaryContainer = text,
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = surfaceHigh, onSurfaceVariant = muted,
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surfaceHigh,
        surfaceContainerHigh = surfaceHigh, surfaceContainerHighest = border,
        inverseSurface = hero, inverseOnSurface = onHero, outline = border, outlineVariant = border,
        error = error, onError = surface, errorContainer = selection, onErrorContainer = text
    )
}

// ---------------------------------------------------------------- settings

enum class TextAlignMode(val title: String) { LEFT("По левому краю"), JUSTIFY("По ширине") }

enum class ReadingMode(val title: String) { PAGES("Страницы"), SCROLL("Прокрутка") }

/** Reader appearance, saved per device. Ranges are enforced on load. */
data class Appearance(
    val theme: ReaderTheme = ReaderTheme.AUTO,
    /** null: the theme's own default font. */
    val hanFont: HanFont? = null,
    val fontSize: Int = 22,
    /** Extra space between lines, as a share of the character height. */
    val lineSpacing: Float = 0.35f,
    val paragraphSpacing: Int = 14,
    val margin: Int = 22,
    val align: TextAlignMode = TextAlignMode.JUSTIFY,
    val indent: Boolean = true,
    /** Pinyin size relative to the characters. */
    val pinyinScale: Float = 0.5f,
    val mode: ReadingMode = ReadingMode.PAGES,
    /** Volume keys turn pages in page mode. */
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Screen brightness while reading, 0..1; negative = the system setting. */
    val brightness: Float = -1f,
    /** Underline words not known yet (red line). */
    val markUnknown: Boolean = false,
    /** Underline words recommended for the reader's level (blue line). */
    val markRecommended: Boolean = false,
    /** How many recommended words a chapter shows at most. */
    val recommendedPerChapter: Int = 12
) {
    fun hanFontFor(spec: ThemeSpec): HanFont = hanFont ?: spec.defaultHanFont

    companion object {
        val FONT_SIZES = 16..34
        val LINE_SPACING = 0.1f..0.9f
        val PARAGRAPH_SPACING = 4..32
        val MARGINS = 8..44
        val PINYIN_SCALE = 0.38f..0.7f
        val RECOMMENDED = 3..40
    }
}

/** One process-wide appearance, observed by the app theme and the reader. */
object AppearanceStore {
    private const val PREFS = "reader_appearance"
    private val state = MutableStateFlow(Appearance())
    private var loaded = false
    val appearance: StateFlow<Appearance> = state.asStateFlow()

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Appearance()
        state.value = Appearance(
            theme = ReaderTheme.entries.firstOrNull { it.name == p.getString("theme", null) } ?: d.theme,
            hanFont = HanFont.entries.firstOrNull { it.name == p.getString("hanFont", null) },
            fontSize = p.getInt("fontSize", d.fontSize).coerceIn(Appearance.FONT_SIZES),
            lineSpacing = p.getFloat("lineSpacing", d.lineSpacing).coerceIn(Appearance.LINE_SPACING),
            paragraphSpacing = p.getInt("paragraphSpacing", d.paragraphSpacing).coerceIn(Appearance.PARAGRAPH_SPACING),
            margin = p.getInt("margin", d.margin).coerceIn(Appearance.MARGINS),
            align = TextAlignMode.entries.firstOrNull { it.name == p.getString("align", null) } ?: d.align,
            indent = p.getBoolean("indent", d.indent),
            pinyinScale = p.getFloat("pinyinScale", d.pinyinScale).coerceIn(Appearance.PINYIN_SCALE),
            mode = ReadingMode.entries.firstOrNull { it.name == p.getString("mode", null) } ?: d.mode,
            volumeKeys = p.getBoolean("volumeKeys", d.volumeKeys),
            keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn),
            brightness = p.getFloat("brightness", d.brightness).coerceAtMost(1f),
            markUnknown = p.getBoolean("markUnknown", d.markUnknown),
            markRecommended = p.getBoolean("markRecommended", d.markRecommended),
            recommendedPerChapter = p.getInt("recommendedPerChapter", d.recommendedPerChapter).coerceIn(Appearance.RECOMMENDED)
        )
        appContext = context.applicationContext
    }

    private var appContext: Context? = null

    fun update(transform: (Appearance) -> Appearance) {
        val next = transform(state.value)
        state.value = next
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.apply {
            putString("theme", next.theme.name)
            if (next.hanFont == null) remove("hanFont") else putString("hanFont", next.hanFont.name)
            putInt("fontSize", next.fontSize)
            putFloat("lineSpacing", next.lineSpacing)
            putInt("paragraphSpacing", next.paragraphSpacing)
            putInt("margin", next.margin)
            putString("align", next.align.name)
            putBoolean("indent", next.indent)
            putFloat("pinyinScale", next.pinyinScale)
            putString("mode", next.mode.name)
            putBoolean("volumeKeys", next.volumeKeys)
            putBoolean("keepScreenOn", next.keepScreenOn)
            putFloat("brightness", next.brightness)
            putBoolean("markUnknown", next.markUnknown)
            putBoolean("markRecommended", next.markRecommended)
            putInt("recommendedPerChapter", next.recommendedPerChapter)
            apply()
        }
    }
}

/** Volume-key page turns: the reader switches this on, MainActivity forwards the keys. */
object ReaderKeys {
    @Volatile var active: Boolean = false
    val turns = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 8)
}

/** A book file another app asked HanReader to open (VIEW / SEND), waiting to be imported. */
object IncomingBooks {
    val pending = MutableStateFlow<android.net.Uri?>(null)

    fun take(intent: android.content.Intent?) {
        intent ?: return
        val uri = when (intent.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND ->
                if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        pending.value = uri
    }
}
