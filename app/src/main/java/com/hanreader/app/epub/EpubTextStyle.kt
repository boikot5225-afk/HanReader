package com.hanreader.app.epub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Structural formatting that survives the reader's own fonts, colours and spacing. */
data class EpubTextStyle(
    val inset: Boolean = false,
    val indent: Boolean? = null,
    val alignment: String? = null,
    val compactBefore: Boolean = false,
    /** Authored structural whitespace, in reader font em, separate from source text offsets. */
    val spaceBeforeEm: Float = 0f,
    val spaceAfterEm: Float = 0f
)

/** A deliberately small CSS reader: only paragraph geometry, never publisher fonts or colours. */
internal class EpubStyles(doc: Document, sheets: List<String>) {
    private val styles = HashMap<Element, MutableMap<String, String>>()
    private val priorities = HashMap<Element, MutableMap<String, Int>>()

    init {
        for (sheet in sheets) {
            val css = sheet.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            // Android uses ICU: unlike the host JVM, it rejects an unescaped closing brace.
            for (rule in Regex("([^{}]+)\\{([^{}]*)\\}").findAll(css)) {
                val declarations = declarations(rule.groupValues[2])
                if (declarations.isEmpty()) continue
                for (selector in rule.groupValues[1].split(',')) {
                    val elements = runCatching { doc.select(selector.trim()) }.getOrNull() ?: continue
                    val priority = Regex("#[\\w-]+").findAll(selector).count() * 100 +
                        Regex("\\.[\\w-]+|\\[[^]]+]|:[\\w-]+").findAll(selector).count() * 10 +
                        Regex("(?:^|[\\s>+~])[a-zA-Z][\\w-]*").findAll(selector.trim()).count()
                    for (el in elements) {
                        val values = styles.getOrPut(el) { HashMap() }
                        val weights = priorities.getOrPut(el) { HashMap() }
                        for ((key, value) in declarations) if (priority >= (weights[key] ?: -1)) {
                            values[key] = value
                            weights[key] = priority
                        }
                    }
                }
            }
        }
        for (el in doc.select("[style]")) styles.getOrPut(el) { HashMap() }.putAll(declarations(el.attr("style")))
    }

    private fun get(el: Element, key: String): String? = styles[el]?.get(key)

    private fun inset(el: Element): Boolean = (listOf(el) + el.parents()).any {
        it.normalName() == "blockquote" || it.attr("epub:type").split(' ').any { type -> type in setOf("epigraph", "z3998:verse") } ||
            it.normalName() !in setOf("body", "html") && positive(get(it, "margin-left")) && positive(get(it, "margin-right"))
    }

    fun textStyle(el: Element): EpubTextStyle {
        val inset = inset(el)
        val indent = get(el, "text-indent")?.let { if (inset) !zero(it) else if (zero(it)) false else null }
        val align = (listOf(el) + el.parents()).firstNotNullOfOrNull { get(it, "text-align") }
        // Only consecutive, explicitly compact paragraphs in the same quotation lose their gap.
        // An empty paragraph remains a stanza separator even though it is not rendered itself.
        val previous = el.previousElementSibling()
        val compact = inset && previous != null && previous.text().replace('\u00a0', ' ').isNotBlank() &&
            inset(previous) && zero(get(el, "margin-top")) && zero(get(previous, "margin-bottom"))
        return EpubTextStyle(inset, indent, align?.takeIf { inset || it in setOf("center", "right") }, compact,
            (lengthEm(get(el, "margin-top")) + lengthEm(get(el, "padding-top"))).coerceAtMost(MAX_SPACE_EM),
            (lengthEm(get(el, "margin-bottom")) + lengthEm(get(el, "padding-bottom"))).coerceAtMost(MAX_SPACE_EM))
    }

    /** Only used for empty structural blocks, never a fixed height for running text. */
    fun emptyHeightEm(el: Element): Float = maxOf(lengthEm(get(el, "height")), lengthEm(get(el, "min-height")))

    private fun declarations(css: String): Map<String, String> = buildMap {
        for (item in css.split(';')) {
            val pair = item.split(':', limit = 2)
            if (pair.size != 2) continue
            val key = pair[0].trim().lowercase()
            val value = pair[1].trim().lowercase().removeSuffix("!important").trim()
            if (key in setOf("margin", "padding")) {
                val parts = value.split(Regex("\\s+"))
                if (parts.size !in 1..4) continue
                put("$key-top", parts[0]); put("$key-right", parts.getOrElse(1) { parts[0] })
                put("$key-bottom", parts.getOrElse(2) { parts[0] }); put("$key-left", parts.getOrElse(3) { parts.getOrElse(1) { parts[0] } })
            } else if (key in setOf("margin-left", "margin-right", "margin-top", "margin-bottom", "padding-top", "padding-bottom",
                    "text-indent", "text-align", "height", "min-height")) put(key, value)
        }
    }

    companion object {
        const val MAX_SPACE_EM = 8f
        const val BLANK_LINE_EM = 1.2f
        private fun lengthEm(value: String?): Float {
            val m = value?.let { Regex("^([+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))(em|rem|px|pt)?$").matchEntire(it) } ?: return 0f
            val n = m.groupValues[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return 0f
            return (when (m.groupValues[2]) { "em", "rem" -> n; "px" -> n / 16f; "pt" -> n / 12f; else -> 0f }).coerceIn(0f, MAX_SPACE_EM)
        }
    }

    private fun zero(value: String?): Boolean = value != null && Regex("[+-]?0+(?:\\.0+)?(?:[a-z%]+)?").matches(value)
    private fun positive(value: String?): Boolean = value?.let { Regex("^[+]?(\\d*\\.?\\d+)").find(it)?.value?.toFloatOrNull() }?.let { it > 0f } == true
}
