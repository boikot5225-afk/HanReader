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
        val ranges = occurrences(example, word, language)
        if (ranges.isEmpty()) return null
        val out = StringBuilder(example)
        for (r in ranges.sortedByDescending { it.first }) out.replace(r.first, r.last + 1, "____")
        return out.toString()
    }

    /**
     * Character ranges of the headword inside an example, using the same rules as [cloze].
     * Spanish cards keep the dictionary form (hablar) while the book has a form of it (habló):
     * without an exact match, a word that starts with the stem is taken (habl-). Irregular forms
     * (fue for ir) are not found, and the example stays hidden as before.
     */
    fun occurrences(example: String, word: String, language: String): List<IntRange> {
        if (word.isBlank() || example.isBlank()) return emptyList()
        val exact = headwordRegex(word, language).findAll(example).map { it.range }.toList()
        if (exact.isNotEmpty() || !language.startsWith("es")) return exact
        val stem = spanishStem(word) ?: return emptyList()
        return Regex("[\\p{L}]+").findAll(example).filter { m ->
            val w = unaccent(m.value.lowercase(Locale.ROOT))
            w.startsWith(stem) && m.value.length <= word.length + 5
        }.map { it.range }.toList()
    }

    /** habl for hablar, com for comer, cas for casa; null when too short to be safe. */
    private fun spanishStem(word: String): String? {
        val w = unaccent(word.trim().lowercase(Locale.ROOT))
        if (w.contains(' ')) return null
        val stem = when {
            w.length >= 5 && (w.endsWith("ar") || w.endsWith("er") || w.endsWith("ir")) -> w.dropLast(2)
            w.length >= 6 && (w.endsWith("arse") || w.endsWith("erse") || w.endsWith("irse")) -> w.dropLast(4)
            w.length >= 4 && w.last() in "aeo" -> w.dropLast(1)
            else -> w
        }
        return stem.takeIf { it.length >= 3 }
    }

    private fun unaccent(s: String): String = s.map { c ->
        when (c) { 'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú' -> 'u'; else -> c }
    }.joinToString("")

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
