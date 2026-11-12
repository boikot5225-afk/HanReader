package com.hanreader.app.epub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Structural formatting that survives the reader's own fonts, colours and spacing. */
data class EpubTextStyle(
    val inset: Boolean = false,
    val indent: Boolean? = null,
    val alignment: String? = null,
    val compactBefore: Boolean = false
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
        if (!inset) return EpubTextStyle()
        val indent = get(el, "text-indent")?.let { !zero(it) }
        val align = (listOf(el) + el.parents()).firstNotNullOfOrNull { get(it, "text-align") }
        // Only consecutive, explicitly compact paragraphs in the same quotation lose their gap.
        // An empty paragraph remains a stanza separator even though it is not rendered itself.
        val previous = el.previousElementSibling()
        val compact = inset && previous != null && previous.text().replace('\u00a0', ' ').isNotBlank() &&
            inset(previous) && zero(get(el, "margin-top")) && zero(get(previous, "margin-bottom"))
        return EpubTextStyle(inset, indent, align, compact)
    }

    private fun declarations(css: String): Map<String, String> = css.split(';').mapNotNull {
        val pair = it.split(':', limit = 2)
        if (pair.size != 2) null else pair[0].trim().lowercase() to pair[1].trim().lowercase().removeSuffix("!important").trim()
    }.filter { it.first in setOf("margin-left", "margin-right", "margin-top", "margin-bottom", "text-indent", "text-align") }.toMap()

    private fun zero(value: String?): Boolean = value != null && Regex("[+-]?0+(?:\\.0+)?(?:[a-z%]+)?").matches(value)
    private fun positive(value: String?): Boolean = value?.let { Regex("^[+]?(\\d*\\.?\\d+)").find(it)?.value?.toFloatOrNull() }?.let { it > 0f } == true
}
