package com.hanreader.app.ai

/** Fixed, non-sensitive diagnostics. Never expose provider text or parser exception messages. */
enum class AiResponseProblem(val description: String) {
    FORMAT("неверная структура JSON"),
    TERMS("не совпадают идентификаторы или количество слов"),
    SOURCE("изменено исходное слово или словарное значение"),
    VALUE("неверный перевод, пояснение или уровень уверенности"),
    SPAN("выражение не удалось однозначно сопоставить с текстом"),
    PROTECTED("выражение затрагивает ручной перевод"),
    CONFLICT("ответ содержит противоречащие друг другу исправления")
}

class AiResponseFailure(val problem: AiResponseProblem) : IllegalArgumentException(problem.name)
