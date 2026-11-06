package com.hanreader.app.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanreader.app.Appearance
import com.hanreader.app.AppearanceStore
import com.hanreader.app.HanFont
import com.hanreader.app.LexiconIcons
import com.hanreader.app.LocalThemeSpec
import com.hanreader.app.ReaderTheme
import com.hanreader.app.TextAlignMode
import com.hanreader.app.WordFont
import com.hanreader.app.zh.TokenKind
import java.util.Locale
import kotlin.math.roundToInt

/** Sample paragraph written for the preview; pinyin over the words a learner may not know. */
private val SAMPLE = ReaderBlock.Text(
    "那天晚上一直在下雨。街灯很暗，他站在门口，等一个不会来的人。",
    heading = false,
    tokens = listOf(
        t("那天", 0), t("晚上", 2), t("一直", 4), t("在", 6), t("下雨", 7), p("。", 9),
        t("街灯", 10, "jiēdēng"), t("很", 12), t("暗", 13, "àn"), p("，", 14), t("他", 15), t("站", 16),
        t("在", 17), t("门口", 18), p("，", 20), t("等", 21), t("一个", 22), t("不会", 24), t("来", 26),
        t("的", 27), t("人", 28), p("。", 29)
    )
)

private fun t(w: String, at: Int, pinyin: String = "") = RToken(w, pinyin, TokenKind.HAN, at, at + w.length, true)
private fun p(w: String, at: Int) = RToken(w, "", TokenKind.PUNCT, at, at + w.length, false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(onDismiss: () -> Unit) {
    val a by AppearanceStore.appearance.collectAsState()
    val spec = LocalThemeSpec.current
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 760.dp).verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Оформление", style = MaterialTheme.typography.titleLarge)

            // Live preview
            val measurer = rememberTextMeasurer(cacheSize = 0)
            val han = WordFont
            val metrics = rememberRubyMetrics(measurer, a, spec, han)
            Box(
                Modifier.fillMaxWidth().background(spec.background).border(1.dp, spec.border, RoundedCornerShape(spec.corner))
                    .padding(horizontal = a.margin.coerceAtMost(28).dp, vertical = 14.dp)
            ) {
                RubyParagraph(
                    SAMPLE, metrics, showPinyin = true, hidePinyinFor = null, saved = setOf("门口"),
                    selection = null, colors = rubyColors(a, spec), onTap = {}
                )
            }

            Section("Тема")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ReaderTheme.entries) { theme -> ThemeCard(theme, selected = a.theme == theme) { AppearanceStore.update { it.copy(theme = theme) } } }
            }

            Section("Шрифт иероглифов")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val current = a.hanFontFor(spec)
                HanFont.entries.forEach { f ->
                    FontRow(f, selected = current == f) { AppearanceStore.update { it.copy(hanFont = f) } }
                }
                if (a.hanFont != null) {
                    TextButton(onClick = { AppearanceStore.update { it.copy(hanFont = null) } }) { Text("Шрифт по теме") }
                }
            }

            Section("Текст")
            Stepper("Размер", "${a.fontSize}",
                onMinus = { AppearanceStore.update { it.copy(fontSize = (it.fontSize - 1).coerceIn(Appearance.FONT_SIZES)) } },
                onPlus = { AppearanceStore.update { it.copy(fontSize = (it.fontSize + 1).coerceIn(Appearance.FONT_SIZES)) } })
            LabeledSlider("Интервал между строками", String.format(Locale.ROOT, "%.0f%%", a.lineSpacing * 100),
                a.lineSpacing, Appearance.LINE_SPACING) { v -> AppearanceStore.update { it.copy(lineSpacing = (v * 20).roundToInt() / 20f) } }
            LabeledSlider("Отступ между абзацами", "${a.paragraphSpacing}",
                a.paragraphSpacing.toFloat(), Appearance.PARAGRAPH_SPACING.first.toFloat()..Appearance.PARAGRAPH_SPACING.last.toFloat()) { v ->
                AppearanceStore.update { it.copy(paragraphSpacing = v.roundToInt()) }
            }
            LabeledSlider("Поля", "${a.margin}",
                a.margin.toFloat(), Appearance.MARGINS.first.toFloat()..Appearance.MARGINS.last.toFloat()) { v ->
                AppearanceStore.update { it.copy(margin = v.roundToInt()) }
            }
            LabeledSlider("Размер пиньиня", String.format(Locale.ROOT, "%.0f%%", a.pinyinScale * 100),
                a.pinyinScale, Appearance.PINYIN_SCALE) { v -> AppearanceStore.update { it.copy(pinyinScale = (v * 50).roundToInt() / 50f) } }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAlignMode.entries.forEach { m ->
                    FilterChip(selected = a.align == m, onClick = { AppearanceStore.update { it.copy(align = m) } }, label = { Text(m.title) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Красная строка", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = a.indent, onCheckedChange = { on -> AppearanceStore.update { it.copy(indent = on) } })
            }
            HorizontalDivider(color = scheme.outlineVariant)
            TextButton(onClick = { AppearanceStore.update { Appearance(theme = it.theme) } }) { Text("Сбросить настройки текста") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ThemeCard(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    val s = theme.resolve(isSystemInDarkTheme()).spec
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = s.background,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else s.border),
        modifier = Modifier.width(112.dp).height(118.dp)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("hàn", color = s.pinyin, fontFamily = s.pinyinFont, fontSize = 11.sp)
            Text("汉字", color = s.text, fontFamily = s.defaultHanFont.family, fontSize = 24.sp)
            Box(Modifier.width(28.dp).height(3.dp).background(s.accent))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(theme.title, color = s.text, fontFamily = s.display, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (selected) Icon(LexiconIcons.Check, contentDescription = "Выбрана", tint = s.accentText, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun FontRow(font: HanFont, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .background(if (selected) scheme.secondaryContainer else scheme.surface, RoundedCornerShape(LocalThemeSpec.current.corner))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(font.sample, fontFamily = font.family, fontSize = 24.sp, modifier = Modifier.widthIn(min = 112.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(font.title, style = MaterialTheme.typography.bodyLarge)
            Text(font.style, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (selected) Icon(LexiconIcons.Check, contentDescription = "Выбран", tint = scheme.primary)
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onMinus) { Text("A−") }
        Text(value, Modifier.widthIn(min = 32.dp), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onPlus) { Text("A+") }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = current, onValueChange = onChange, valueRange = range)
    }
}
