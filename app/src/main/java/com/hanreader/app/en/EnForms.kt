package com.hanreader.app.en

import java.text.Normalizer
import java.util.Locale

/** Alternatives are deliberately preserved until a sentence supplies enough context. */
data class EnExpansion(val words: List<String>) {
    val text: String get() = words.joinToString(" ")
}

data class EnPossessive(val base: String, val pluralSpelling: Boolean)

data class EnSurfaceForm(
    val text: String,
    val expansions: List<EnExpansion> = emptyList(),
    val possessive: EnPossessive? = null
)

object EnForms {
    fun apostrophes(word: String): String = Normalizer.normalize(word.trim(), Normalizer.Form.NFC)
        .replace('’', '\'').replace('‘', '\'')

    fun key(word: String): String = apostrophes(word).lowercase(Locale.ROOT)

    private val subjects = setOf("i", "you", "he", "she", "it", "we", "they", "who", "what", "where",
        "when", "why", "how", "that", "there", "here", "this")
    private val negative = setOf("is", "are", "was", "were", "do", "does", "did", "have", "has", "had",
        "can", "could", "will", "would", "shall", "should", "must", "might", "need", "dare", "ought")
    private val haveAfter = subjects + setOf("could", "would", "should", "might", "must", "may")

    fun parse(word: String): EnSurfaceForm {
        val raw = apostrophes(word)
        val lower = key(raw)
        fun result(vararg phrases: List<String>, possessive: EnPossessive? = null) =
            EnSurfaceForm(raw, phrases.map { EnExpansion(it) }.distinct(), possessive)
        when (lower) {
            "let's" -> return result(listOf("let", "us"))
            "can't" -> return result(listOf("can", "not"))
            "won't" -> return result(listOf("will", "not"))
            "shan't" -> return result(listOf("shall", "not"))
            "ain't" -> return result(*listOf("am", "is", "are", "has", "have").map { listOf(it, "not") }.toTypedArray())
            "'em" -> return result(listOf("them"))
            "'cause" -> return result(listOf("because"))
            "ma'am" -> return result(listOf("madam"))
        }
        if (lower.endsWith("n't")) {
            val base = lower.dropLast(3)
            if (base in negative) return result(listOf(base, "not"))
        }
        if (lower.endsWith("'ve")) {
            val base = raw.dropLast(3)
            val baseKey = key(base)
            if (baseKey in haveAfter) return result(listOf(if (baseKey == "i") "I" else baseKey, "have"))
            // I'd've = I would have; "I had have" is not an alternative expansion.
            if (baseKey.endsWith("'d") && baseKey.dropLast(2) in subjects) {
                val subject = baseKey.dropLast(2).let { if (it == "i") "I" else it }
                return result(listOf(subject, "would", "have"))
            }
            val expanded = parseBase(base)
            if (expanded.isNotEmpty()) return result(*expanded.map { it.words + "have" }.toTypedArray())
        }
        for ((suffix, auxiliary) in listOf("'m" to "am", "'re" to "are", "'ll" to "will", "'d" to "would")) {
            if (!lower.endsWith(suffix)) continue
            val base = lower.dropLast(suffix.length)
            if (base !in subjects || (suffix == "'m" && base != "i")) continue
            val subject = if (base == "i") "I" else base
            return if (suffix == "'d") result(listOf(subject, "had"), listOf(subject, "would"))
            else result(listOf(subject, auxiliary))
        }
        if (lower.endsWith("'s") && raw.length > 2) {
            val base = raw.dropLast(2)
            val baseKey = key(base)
            if (baseKey in subjects && baseKey !in setOf("i", "you", "we", "they")) {
                return result(listOf(baseKey, "is"), listOf(baseKey, "has"))
            }
            if (base.all { it.isLetter() || it == '-' || it == '\'' }) {
                // The consumer can reject singular auxiliaries after a plural noun.
                return result(listOf(base, "is"), listOf(base, "has"), possessive = EnPossessive(base, false))
            }
        }
        if (lower.endsWith("s'") && raw.length > 2) {
            return result(possessive = EnPossessive(raw.dropLast(1), true))
        }
        return EnSurfaceForm(raw)
    }

    /** Bound recursion for compounds such as couldn't've. */
    private fun parseBase(base: String): List<EnExpansion> =
        if (key(base).endsWith("n't")) parse(base).expansions else emptyList()
}
