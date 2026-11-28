package com.hanreader.app.reader

import com.hanreader.app.WordCard
import com.hanreader.app.isLearned

/**
 * How a word in the cards behaves in the text. A fresh card keeps its translation above the word, so
 * adding a word never takes its hint away; after [HINT_REVIEWS] successful reviews the hint goes and the
 * underline asks to recall it; a learned word is plain text. A failed review brings the hint back.
 */
enum class StudyStage(val label: String, val hint: Boolean) {
    NEW("новое слово", true),
    LEARNING("учу — подсказка над словом", true),
    RECALL("вспоминаю — без подсказки", false),
    LEARNED("выучено", false)
}

object StudyStages {
    const val HINT_REVIEWS = 2
    private const val DAY_MS = 86_400_000L

    fun of(card: WordCard): StudyStage = when {
        card.isLearned() -> StudyStage.LEARNED
        card.introducedAt == 0L && card.successfulReviews == 0 -> StudyStage.NEW
        card.successfulReviews < HINT_REVIEWS -> StudyStage.LEARNING
        else -> StudyStage.RECALL
    }

    /** The card's translation as a hint: its first meaning, short. */
    fun hint(translation: String): String? {
        val first = translation.split(';', '；', ',', '，', '/', '\n').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return if (first.length <= MAX_HINT) first else first.take(MAX_HINT - 1).trimEnd() + "…"
    }
    private const val MAX_HINT = 28

    /** Hints of the words whose cards still show them; a word in several cards takes the newest card's. */
    fun hints(cards: List<WordCard>): Map<String, String> {
        val out = HashMap<String, String>()
        for (c in cards.sortedByDescending { it.createdAt }) {
            if (c.word in out || !of(c).hint) continue
            hint(c.translation)?.let { out[c.word] = it }
        }
        // A word with any card past the hint stage is being recalled: no hint from another of its cards.
        cards.filter { of(it) == StudyStage.RECALL || of(it) == StudyStage.LEARNED }.forEach { out.remove(it.word) }
        return out
    }

    /**
     * The reader opened the card of a word whose hint is already gone: it needed help, so the next
     * review comes within a day. Progress is kept; null when nothing changes.
     */
    fun peeked(card: WordCard, now: Long): WordCard? =
        if (of(card) != StudyStage.RECALL || card.nextReviewAt <= now + DAY_MS) null
        else card.copy(nextReviewAt = now + DAY_MS)

    /** The card a tapped word is about: the one whose translation fits the meaning here, else the newest. */
    fun cardFor(cards: List<WordCard>, meaningHere: String): WordCard? =
        cards.takeIf { meaningHere.isNotBlank() }?.firstOrNull { !otherMeaning(it, meaningHere) } ?: cards.maxByOrNull { it.createdAt }

    /** The meaning here is none of the card's meanings («банк» on a card «берег; банк» is the same). */
    fun otherMeaning(card: WordCard, meaningHere: String): Boolean {
        fun parts(s: String) = s.lowercase().split(';', '；', ',', '，', '/', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val here = parts(meaningHere)
        val own = parts(card.translation)
        if (here.isEmpty() || own.isEmpty()) return false
        return here.none { h -> own.any { o -> o == h || o.contains(h) || h.contains(o) } }
    }

    /** «Помню» in the text counts as a review only when one is due; never earlier than the schedule. */
    fun canConfirm(card: WordCard, now: Long) = card.nextReviewAt <= now

    /** «через 3 дн.», «сегодня» — when the next recognition review is. */
    fun nextReview(card: WordCard, now: Long): String {
        val days = ((card.nextReviewAt - now + DAY_MS - 1) / DAY_MS)
        return when {
            card.nextReviewAt <= now -> "повтор сейчас"
            days <= 1 -> "повтор в течение суток"
            else -> "повтор через $days дн."
        }
    }
}
