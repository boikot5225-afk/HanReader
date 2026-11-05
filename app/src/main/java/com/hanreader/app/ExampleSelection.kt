package com.hanreader.app

/** One independent SRS word can have multiple contextual examples. */
object ExampleSelection {
    fun choose(card: WordCard, mode: ReviewMode, all: List<CardExample>): CardExample {
        val choices = all.filter { it.cardId == card.id }.sortedBy { it.id }
        if (choices.isEmpty()) {
            return CardExample(
                cardId = card.id,
                example = card.example,
                exampleTranslation = card.exampleTranslation
            )
        }
        return choices[ReviewSchedule.successCount(card, mode) % choices.size]
    }
}
