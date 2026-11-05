package com.hanreader.app

data class ImportCard(
    val line: Int,
    val word: String,
    val reading: String,
    val translation: String,
    val example: String,
    val exampleTranslation: String
)
data class ImportProblem(val line: Int, val message: String)
data class ImportPreview(val cards: List<ImportCard>, val problems: List<ImportProblem>)

object CardImportParser {
    fun parse(text: String): ImportPreview {
        val cards = mutableListOf<ImportCard>()
        val problems = mutableListOf<ImportProblem>()
        text.lineSequence().forEachIndexed { i, raw ->
            if (raw.isBlank()) return@forEachIndexed
            val fields = raw.split('|').map { it.trim() }
            if (fields.size != 5) {
                problems += ImportProblem(i + 1, "Ожидается ровно 5 полей через |")
            } else if (fields[0].isEmpty() || fields[2].isEmpty() ||
                fields[3].isEmpty() || fields[4].isEmpty()
            ) {
                problems += ImportProblem(i + 1, "Не хватает слова, перевода или контекста")
            } else {
                cards += ImportCard(
                    i + 1, fields[0], fields[1], fields[2], fields[3], fields[4]
                )
            }
        }
        return ImportPreview(cards, problems)
    }
}
