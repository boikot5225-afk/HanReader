package com.hanreader.app

import java.text.Normalizer
import java.util.Locale

/** Pure rules for typed recall. Independent of UI and Android services. */
object WritingExercise {
    fun matches(input: String, expected: String): Boolean =
        input.isNotBlank() && normalize(input) == normalize(expected)

    private fun normalize(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)

    /**
     * Hide all exact occurrences of the card's headword.
     * For Latin-script language examples, require token boundaries so "cat"
     * cannot be masked inside "catalog". If no safe match exists, return null:
     * the UI must then hide the entire foreign-language example until grading.
     * This prevents leaking an inflected form as a supposed target answer.
     */
    fun cloze(example: String, word: String, language: String): String? {
        if (word.isBlank() || example.isBlank()) return null
        val regex = headwordRegex(word, language)
        if (!regex.containsMatchIn(example)) return null
        return regex.replace(example, "____")
    }

    /** Character ranges of the headword inside an example, using the same rules as [cloze]. */
    fun occurrences(example: String, word: String, language: String): List<IntRange> {
        if (word.isBlank() || example.isBlank()) return emptyList()
        return headwordRegex(word, language).findAll(example).map { it.range }.toList()
    }

    /**
     * Indices in the trimmed expected answer that the typed answer got wrong
     * (compared position by position, ignoring case). Used only to highlight
     * the mistake after checking; grading itself stays in [matches].
     */
    fun mismatches(input: String, expected: String): Set<Int> {
        val typed = input.trim()
        val answer = expected.trim()
        return answer.indices.filter { i ->
            i >= typed.length || !typed[i].equals(answer[i], ignoreCase = true)
        }.toSet()
    }

    private fun headwordRegex(word: String, language: String): Regex {
        val escaped = Regex.escape(word)
        val pattern = if (language.startsWith("zh") || language.startsWith("ja")) {
            escaped
        } else {
            "(?<![\\p{L}\\p{N}])" + escaped + "(?![\\p{L}\\p{N}])"
        }
        return Regex(pattern, RegexOption.IGNORE_CASE)
    }
}
