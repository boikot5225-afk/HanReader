package com.hanreader.app.lang

/**
 * Meanings chosen by the words around: a word with several senses gets the one its sentence
 * calls for (durmientes near «rieles»: «шпалы», not «спящие»). Rules are data, one per line
 * (assets/<language>/context.tsv), so a reader's book can add them without code:
 *
 *   lemma \t conditions \t meaning \t note
 *
 * Conditions, joined by " & " (all must hold):
 *  - near:a,b,c       — one of these words (or a word whose lemma is one) is in the same sentence;
 *  - after:a b        — these words follow within the next three words («templar … ánimo»);
 *  - before:a b       — these words come within the five words before («echar mano de … expedientes»);
 *  - next:a           — the very next word is one of these («tratar a»);
 *  - after-verb       — the word right before is a verb (an adverb of manner, not a sentence adverb).
 * The first rule of a lemma that holds wins. Words compare in lower case.
 */
class ContextRules private constructor(private val byLemma: Map<String, List<Rule>>) {
    private class Rule(val lemma: String, val conditions: List<Condition>, val meaning: String)

    private sealed interface Condition
    private class Near(val words: Set<String>) : Condition
    private class After(val words: List<String>) : Condition
    private class Before(val words: List<String>) : Condition
    private class Next(val words: Set<String>) : Condition
    private object AfterVerb : Condition

    /** A sentence as the rules see it. */
    class Sentence(
        /** Its words in lower case. */
        val words: List<String>,
        /** The dictionary words each word can be. */
        val lemmas: (Int) -> Set<String>,
        /** Whether the word is a verb here. */
        val isVerb: (Int) -> Boolean
    )

    val isEmpty: Boolean get() = byLemma.isEmpty()

    /** Whether any rule is about one of [lemmas]: a cheap test before building the sentence. */
    fun covers(lemmas: Collection<String>): Boolean = lemmas.any { it in byLemma }

    /** The meaning the rules give word [i] of [s] read as one of [lemmas]; null when no rule holds. */
    fun meaning(lemmas: Collection<String>, s: Sentence, i: Int): String? {
        for (l in lemmas) for (r in byLemma[l].orEmpty()) if (r.conditions.all { holds(it, s, i) }) return r.meaning
        return null
    }

    private fun holds(c: Condition, s: Sentence, i: Int): Boolean = when (c) {
        is Near -> s.words.indices.any { j -> j != i && (s.words[j] in c.words || s.lemmas(j).any { it in c.words }) }
        is Next -> s.words.getOrNull(i + 1)?.let { it in c.words } == true
        is After -> (0..3 - c.words.size).any { off -> c.words.indices.all { k -> s.words.getOrNull(i + 1 + off + k) == c.words[k] } }
        is Before -> (0..5 - c.words.size).any { off ->
            val start = i - c.words.size - off
            start >= 0 && c.words.indices.all { k -> s.words[start + k] == c.words[k] }
        }
        AfterVerb -> i > 0 && s.isVerb(i - 1)
    }

    companion object {
        val EMPTY = ContextRules(emptyMap())

        fun parse(lines: Sequence<String>): ContextRules {
            val rules = ArrayList<Rule>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t')
                if (p.size < 3) continue
                val lemma = p[0].trim().lowercase()
                val conditions = p[1].split(" & ").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { c ->
                    val arg = c.substringAfter(':', "").trim().lowercase()
                    when {
                        c.startsWith("near:") -> Near(arg.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                        c.startsWith("after:") -> After(arg.split(' ').filter { it.isNotEmpty() })
                        c.startsWith("before:") -> Before(arg.split(' ').filter { it.isNotEmpty() })
                        c.startsWith("next:") -> Next(arg.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                        c == "after-verb" -> AfterVerb
                        else -> null
                    }
                }
                val meaning = p[2].trim()
                if (lemma.isEmpty() || conditions.isEmpty() || meaning.isEmpty()) continue
                rules += Rule(lemma, conditions, meaning)
            }
            return ContextRules(rules.groupBy { it.lemma })
        }

        /** The reading key of a token without the meaning a rule chose: "durmiente#шпала" → "durmiente". */
        fun key(reading: String): String = reading.substringBefore('#')

        /** The meaning a rule chose, or null: "durmiente#шпала" → «шпала». */
        fun chosen(reading: String?): String? = reading?.substringAfter('#', "")?.ifEmpty { null }
    }
}
