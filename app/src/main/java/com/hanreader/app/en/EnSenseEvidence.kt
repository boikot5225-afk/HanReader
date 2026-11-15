package com.hanreader.app.en

/** Select only Russian words already attached to an attested dictionary sense label.
 * No book identity, generated translation, lexical cache or network is involved. */
internal object EnSenseEvidence {
    private val WEAPON_LABEL = Regex("\\bweapons?\\b")
    fun select(senses: List<EnTranslation>, surface: String, words: List<String>, lemmas: List<String>, i: Int,
               numberBefore: Boolean): String? {
        fun at(offset: Int) = words.getOrNull(i + offset)
        fun lemma(offset: Int) = lemmas.getOrNull(i + offset)
        val nearby = (maxOf(0, i - 5)..minOf(words.lastIndex, i + 5)).filter { it != i }
        val cues = nearby.flatMap { listOf(words[it], lemmas[it]) }.toSet()
        val body = cues.any { it in setOf("embrace", "fold", "cross", "neck", "shoulder", "hand", "finger") }
        val business = cues.any { it in setOf("business", "corporation", "profit", "shareholder", "sell", "buy", "own", "insurance", "guest") }
        val military = cues.any { it in setOf("army", "infantry", "cavalry", "regiment", "battalion", "platoon", "soldier", "troop", "rifle", "sentinel", "guard") }
        val command = cues.any { it in setOf("command", "order", "lead", "station", "advance", "march") }
        val leader = cues.any { it in setOf("captain", "colonel", "lieutenant", "sergeant", "commander") }
        val possessiveUnit = at(-1) in setOf("his", "her", "their", "our", "my") || at(-1)?.endsWith("'s") == true
        val unitEvidence = !business && ((military && command) || leader && (command || possessiveUnit) ||
            at(1) == "of" && lemma(2) in setOf("infantry", "cavalry", "soldier"))
        val distance = numberBefore && at(1) in setOf("high", "long", "wide", "deep", "tall", "away", "apart", "above", "below", "from") &&
            (1..3).none { at(-it) in setOf("his", "her", "their", "our", "my", "your", "its") } &&
            lemma(-2) !in setOf("have", "grow")
        val waterside = at(1) == "of" && (2..3).any { lemma(it) in setOf("river", "stream", "creek", "lake") }
        val objectOffset = if (at(-1) in setOf("his", "her", "their", "our", "my", "your", "the")) -2 else -1
        val weapons = surface == "arms" && !body && (lemma(objectOffset) in setOf("bear", "carry", "surrender") ||
            at(objectOffset) == "under" || at(objectOffset) in setOf("up", "down") && lemma(objectOffset-1) in setOf("take", "lay", "put"))
        val matches = senses.filter { sense ->
            val label = sense.sense.lowercase()
            when {
                "military unit" in label -> unitEvidence
                "soldiers placed" in label -> military && cues.none { it in setOf("fence", "stake", "strike", "union") }
                "unit of measure" in label || "unit of length" in label -> distance
                "edge" in label && ("river" in label || "lake" in label) -> waterside
                WEAPON_LABEL.containsMatchIn(label) -> weapons
                else -> false
            }
        }.map { it.words }.filter { it.isNotEmpty() }.distinct()
        // Competing labelled senses are not resolved by counting keywords.
        return matches.singleOrNull()?.take(2)?.joinToString("; ")
    }
}
