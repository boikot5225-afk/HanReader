package com.hanreader.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleSelectionTest {
    private val card = WordCard(
        id = 8, language = "fr", word = "fuir", reading = "",
        translation = "бежать", example = "Il doit fuir.",
        exampleTranslation = "Он должен бежать."
    )
    private val examples = listOf(
        CardExample(id = 10, cardId = 8, example = "Il doit fuir.", exampleTranslation = "Он должен бежать."),
        CardExample(id = 11, cardId = 8, example = "Elle veut fuir.", exampleTranslation = "Она хочет бежать."),
        CardExample(id = 12, cardId = 8, example = "Ils vont fuir.", exampleTranslation = "Они собираются бежать.")
    )

    @Test fun cyclesExamplesWithoutCreatingExtraSrsCards() {
        assertEquals("Il doit fuir.", ExampleSelection.choose(card, ReviewMode.RECOGNITION, examples).example)
        assertEquals("Elle veut fuir.", ExampleSelection.choose(card.copy(successfulReviews = 1), ReviewMode.RECOGNITION, examples).example)
        assertEquals("Ils vont fuir.", ExampleSelection.choose(card.copy(writingSuccessfulReviews = 2), ReviewMode.WRITING, examples).example)
        assertEquals("Il doit fuir.", ExampleSelection.choose(card.copy(successfulReviews = 3), ReviewMode.RECOGNITION, examples).example)
    }

    @Test fun fallsBackForOldDataBeforeContextFlowLoads() {
        assertEquals(card.example, ExampleSelection.choose(card, ReviewMode.WRITING, emptyList()).example)
    }
}
