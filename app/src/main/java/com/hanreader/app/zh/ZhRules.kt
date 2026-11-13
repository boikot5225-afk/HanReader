package com.hanreader.app.zh

import java.io.File
import java.io.InputStream

/**
 * Chinese context rules as data (assets/zh/context.tsv), like the Spanish ones: which reading a
 * polyphone takes among its neighbours (慢慢地 de, 很长 cháng) and where a dictionary word is cut in
 * two (却说 after a subject is 却 + 说). One rule per line:
 *
 *   read  \t 字 or 词 \t conditions \t reading ("-" keeps the dictionary's) \t note
 *   split \t 词,词     \t conditions \t where to cut (0 = keep whole)       \t note
 *   set   \t NAME      \t value — then $NAME anywhere in conditions stands for the value
 *
 * Conditions are joined by " & ", all must hold; "!" before one negates it; "*" always holds.
 * prev / next are the neighbouring Chinese words (inside a combination: the rest of it).
 *  - prev, next            — there is one (!prev: the clause starts here);
 *  - prev:CHARS            — prev ends with one of these characters; next:CHARS — next starts with one;
 *  - prev0:CHARS           — prev starts with one of these;
 *  - prev=a,b / next=a,b   — prev / next is one of these words;
 *  - prev~a,b / next^a,b   — prev contains / next starts with one of these;
 *  - long-prev             — prev has two characters or more;
 *  - earlier:a,b           — one of these words in the six before, same clause;
 *  - later:a,b             — one of these words in the eight after, same clause;
 *  - next-any:CHARS        — the next token of any kind (a comma too) starts with one of these.
 * The first rule of a word that holds wins.
 */
class ZhRules private constructor(
    private val reads: Map<String, List<Rule>>,
    private val splits: Map<String, List<Rule>>
) {
    private class Rule(val conditions: List<Condition>, val result: String)

    private class Condition(val kind: String, val arg: String, val negated: Boolean) {
        val chars: String = arg
        val words: Set<String> = arg.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Where a rule looks: [prev] / [next] words, and the tokens when the word is a token of its own. */
    private class Place(val prev: String?, val next: String?, val tokens: List<ZhToken>?, val i: Int)

    val isEmpty: Boolean get() = reads.isEmpty() && splits.isEmpty()

    /** Whether [word] has reading rules. */
    fun readsOf(word: String): Boolean = word in reads

    /**
     * The reading the rules give [word] between [prev] and [next]: null when no rule holds,
     * "" when a rule keeps the dictionary's. [tokens] and [i] for the clause-wide conditions.
     */
    fun reading(word: String, prev: String?, next: String?, tokens: List<ZhToken>? = null, i: Int = -1): String? =
        first(reads[word], Place(prev, next, tokens, i))?.let { if (it == "-") "" else it }

    /** Where token [i] is cut by a rule; null when no rule is about it. */
    fun splitAt(tokens: List<ZhToken>, i: Int, prev: String?, next: String?): Int? {
        val rules = splits[tokens[i].text] ?: return null
        val cut = first(rules, Place(prev, next, tokens, i))?.toIntOrNull() ?: 0
        return if (cut in 1 until tokens[i].text.length) cut else 0
    }

    private fun first(rules: List<Rule>?, p: Place): String? =
        rules?.firstOrNull { r -> r.conditions.all { holds(it, p) != it.negated } }?.result

    private fun holds(c: Condition, p: Place): Boolean {
        val prev = p.prev
        val next = p.next
        return when (c.kind) {
            "*" -> true
            "prev" -> prev != null
            "next" -> next != null
            "long-prev" -> prev != null && prev.length >= 2
            "prev:" -> prev != null && prev.last() in c.chars
            "prev0:" -> prev != null && prev[0] in c.chars
            "next:" -> next != null && next[0] in c.chars
            "prev=" -> prev != null && prev in c.words
            "next=" -> next != null && next in c.words
            "prev~" -> prev != null && c.words.any { prev.contains(it) }
            "next^" -> next != null && c.words.any { next.startsWith(it) }
            "next-any:" -> p.tokens?.getOrNull(p.i + 1)?.text?.firstOrNull()?.let { it in c.chars } == true
            "earlier:" -> p.tokens != null && earlier(p.tokens, p.i, c.words)
            "later:" -> p.tokens != null && later(p.tokens, p.i, c.words)
            else -> false
        }
    }

    /** One of [words] is among the six tokens before [i] in the same clause. */
    private fun earlier(tokens: List<ZhToken>, i: Int, words: Set<String>): Boolean {
        var k = i - 1
        while (k >= 0 && k >= i - 6) {
            val t = tokens[k]
            if (t.kind == TokenKind.PUNCT) return false
            if (t.text in words) return true
            k--
        }
        return false
    }

    /** One of [words] is among the eight tokens after [i], before the clause ends. */
    private fun later(tokens: List<ZhToken>, i: Int, words: Set<String>): Boolean {
        var k = i + 1
        while (k < tokens.size && k <= i + 8) {
            val t = tokens[k]
            if (t.kind == TokenKind.PUNCT && t.text[0] in "，。！？；,.!?;、：:") return false
            if (t.text in words) return true
            k++
        }
        return false
    }

    companion object {
        val EMPTY = ZhRules(emptyMap(), emptyMap())

        /** The rules in use: assets/zh/context.tsv once the Chinese engine has loaded. */
        @Volatile
        var current: ZhRules = EMPTY

        private val KINDS = listOf("next-any:", "earlier:", "later:", "prev0:", "prev:", "next:", "prev=", "next=", "prev~", "next^")

        fun load(input: InputStream): ZhRules = input.bufferedReader().useLines { parse(it) }

        fun load(file: File): ZhRules = file.inputStream().use { load(it) }

        fun parse(lines: Sequence<String>): ZhRules {
            val sets = LinkedHashMap<String, String>()
            val reads = LinkedHashMap<String, MutableList<Rule>>()
            val splits = LinkedHashMap<String, MutableList<Rule>>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val p = line.split('\t')
                if (p.size < 3) continue
                val kind = p[0].trim()
                if (kind == "set") { sets[p[1].trim()] = p[2].trim(); continue }
                if (p.size < 4 || kind != "read" && kind != "split") continue
                var text = p[2].trim()
                // Longer names first: $COUNT_CHARS is not $COUNT + "_CHARS".
                for (name in sets.keys.sortedByDescending { it.length }) text = text.replace("$$name", sets.getValue(name))
                val conditions = text.split(" & ").map { it.trim() }.filter { it.isNotEmpty() }.map { raw ->
                    val negated = raw.startsWith("!")
                    val c = raw.removePrefix("!")
                    val k = KINDS.firstOrNull { c.startsWith(it) }
                    if (k != null) Condition(k, c.substring(k.length), negated) else Condition(c, "", negated)
                }
                val result = p[3].trim()
                if (conditions.isEmpty() || result.isEmpty()) continue
                val target = if (kind == "read") reads else splits
                for (w in p[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }) target.getOrPut(w) { ArrayList() } += Rule(conditions, result)
            }
            return ZhRules(reads, splits)
        }
    }
}
