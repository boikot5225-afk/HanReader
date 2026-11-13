package com.hanreader.app.es

import android.content.Context
import com.hanreader.app.lang.LanguageInfo
import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.lang.Languages
import com.hanreader.app.lang.PreparedText
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.reader.RToken
import com.hanreader.app.zh.TokenKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A Spanish dictionary word (the shared [com.hanreader.app.lang.LexEntry]). */
typealias EsEntry = com.hanreader.app.lang.LexEntry

/**
 * The variant of Spanish whose regional senses come first. Senses carry their region in a label:
 * «(Исп.) компьютер», «(Лат. Ам.) автомобиль», «(Мекс., разг.) работа».
 */
enum class EsVariant(val title: String, private val marks: List<String>) {
    ANY("Не важно", emptyList()),
    SPAIN("Испания", listOf("Исп", "Канары", "Андалус")),
    LATAM("Лат. Америка", listOf("Лат. Ам", "Мекс", "Ла-Плата", "Аргент", "Уругв", "Чили", "Колумб", "Перу", "Венес",
        "Куб", "Центр", "Карибы", "Эквад", "Болив", "Гват", "Пуэрто", "Доминик", "Парагв", "Гондур", "Никараг", "Сальв", "Коста", "Панам", "Юж. Ам"));

    /** 0: a sense of this variant, 1: a sense used everywhere, 2: a sense of another region. */
    fun rank(sense: String): Int {
        if (this == ANY || !sense.startsWith("(")) return 1
        val label = sense.substringAfter("(").substringBefore(")")
        val regional = (SPAIN.marks + LATAM.marks).any { it in label }
        return when {
            marks.any { it in label } -> 0
            regional -> 2
            else -> 1
        }
    }
}

/** One reading of a word on the page: hablo → hablar, «1 л. ед. ч., наст.». */
typealias EsAnalysis = com.hanreader.app.lang.LexAnalysis

/**
 * The Spanish language module. Words are runs of letters; a word on the page is looked up as it
 * is, then as a form of a dictionary word (hablo → hablar, fue → ir / ser), then with the
 * pronouns attached to a verb taken off (dámelo → dar + me + lo).
 */
class EsEngine(
    private val dict: SortedTsv,
    private val forms: SortedTsv,
    private val grammar: List<String>,
    override val loadMs: Long = 0,
    /** Meanings the words around choose (assets/es/context.tsv): durmientes near «rieles» — «шпалы». */
    private val rules: com.hanreader.app.lang.ContextRules = com.hanreader.app.lang.ContextRules.EMPTY
) : com.hanreader.app.lang.AlphabeticModule {
    override val info: LanguageInfo get() = Languages.SPANISH

    private val cache = object : LinkedHashMap<String, List<EsAnalysis>>(2048, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EsAnalysis>>?) = size > 20_000
    }

    fun entry(lemma: String): EsEntry? {
        val f = dict.get(lemma) ?: return null
        fun senses(i: Int) = f.getOrNull(i).orEmpty().split(" ; ").map { it.trim() }.filter { it.isNotEmpty() }
        val pos = f.getOrNull(0).orEmpty()
        val machine = f.getOrNull(3) == "a" || f.getOrNull(3) == "r"
        val rank = f.getOrNull(4)?.toIntOrNull() ?: 0
        val gender = f.getOrNull(6).orEmpty()
        // Other parts of speech: «сущ.|имущество|имущество ; актив|asset ; credit», several joined by « || ».
        val alts = f.getOrNull(7).orEmpty().split(" || ").filter { it.isNotEmpty() }.map { alt ->
            val a = alt.split('|')
            fun list(i: Int) = a.getOrNull(i).orEmpty().split(" ; ").map { it.trim() }.filter { it.isNotEmpty() }
            EsEntry(lemma, a[0], list(2), list(3), machine = true, rank = rank, short = a.getOrNull(1).orEmpty(),
                gender = if (a[0] == "сущ.") gender else "", key = lemma + "/" + a[0])
        }
        return EsEntry(lemma, pos, senses(1), senses(2), machine = machine, rank = rank, short = f.getOrNull(5).orEmpty(),
            gender = if (pos == "сущ.") gender else "", alts = alts)
    }

    /** Every dictionary reading of [word], the most useful first (a word with a Russian meaning first). */
    fun analyses(word: String): List<EsAnalysis> {
        val w = word.lowercase()
        synchronized(cache) { cache[w] }?.let { return it }
        val self = entry(w)?.let { e -> listOf(EsAnalysis(w, e, "")) + e.alts.map { EsAnalysis(w, it, "") } }.orEmpty()
        val asForm = ArrayList<EsAnalysis>(2)
        // "!": a frequent verb form that is also a rare word (era: ser before «эра»).
        val f = forms.get(w)?.firstOrNull().orEmpty()
        val formFirst = f.startsWith("!")
        f.removePrefix("!").split('|').filter { it.isNotEmpty() }.forEach { item ->
            // «haber:3:сущ.»: the dictionary word, its grammar, and the part of speech when it is not the word's main one.
            val part = item.split(':')
            val lemma = part[0]
            val g = part.getOrNull(1)?.toIntOrNull()?.let { grammar.getOrNull(it) }.orEmpty()
            val e = entry(lemma)?.let { e -> part.getOrNull(2)?.let { p -> e.alts.firstOrNull { it.pos == p } } ?: e }
            if (lemma != w && e != null && asForm.none { it.entry.key == e.key }) asForm += EsAnalysis(w, e, g)
        }
        val out = ArrayList<EsAnalysis>(3)
        // A form with an entry of its own whose main sense is regional or marked (jefes «(Мекс.) родители»,
        // bolsas «(Венес.) придурок»): the ordinary word it is a form of comes first (jefe «начальник»).
        val marked = self.firstOrNull()?.entry?.ru?.firstOrNull()?.startsWith("(") == true &&
            asForm.any { f -> f.entry.ru.firstOrNull()?.let { !it.startsWith("(") } == true }
        if (formFirst || marked) { out += asForm; out += self } else { out += self; out += asForm }
        // A form with no grammar of its own that is a verb with pronouns attached (dámelo): the rule
        // names the form and the pronouns («повел. + me lo»).
        if (out.isEmpty() || out.all { it.grammar.isEmpty() && it.entry.pos == "гл." && it.entry.lemma != w }) {
            withoutPronouns(w).takeIf { it.isNotEmpty() }?.let { out.clear(); out += it }
        }
        // A reading with a Russian meaning first, unless the order was set on purpose ("!").
        val sorted = if (formFirst) out else out.sortedBy { if (it.entry.ru.isNotEmpty()) 0 else 1 }
        synchronized(cache) { cache[w] = sorted }
        return sorted
    }

    /** dámelo → dar, «повел. + me lo»: up to two attached pronouns, the stress mark they forced dropped. */
    private fun withoutPronouns(w: String): List<EsAnalysis> {
        for (p1 in PRONOUNS) {
            if (!w.endsWith(p1) || w.length - p1.length < 2) continue
            val one = w.dropLast(p1.length)
            verb(one, p1)?.let { return listOf(it) }
            for (p2 in PRONOUNS) {
                if (!one.endsWith(p2) || one.length - p2.length < 2) continue
                verb(one.dropLast(p2.length), "$p2 $p1")?.let { return listOf(it) }
            }
        }
        return emptyList()
    }

    /** A verb form (infinitive, gerund, imperative) under [stem], with or without its stress mark. */
    private fun verb(stem: String, pronouns: String): EsAnalysis? {
        val plus = "+ $pronouns"
        for (s in listOf(stem, unaccent(stem)).distinct()) {
            // A form first (hablando -> hablar), even if the form has an entry of its own.
            forms.get(s)?.firstOrNull()?.removePrefix("!")?.split('|')?.forEach { item ->
                val part = item.split(':')
                entry(part[0])?.let { e -> part.getOrNull(2)?.let { p -> e.alts.firstOrNull { it.pos == p } } ?: e }?.takeIf { it.pos == "гл." }?.let { e ->
                    val g = part.getOrNull(1)?.toIntOrNull()?.let { grammar.getOrNull(it) }.orEmpty()
                    return EsAnalysis(stem, e, if (g.isEmpty()) plus else "$g $plus")
                }
            }
            entry(s)?.let { e -> if (e.pos == "гл.") e else e.alts.firstOrNull { it.pos == "гл." } }?.let { return EsAnalysis(stem, it, "инфинитив $plus") }
        }
        return null
    }

    /**
     * The longest dictionary phrase of 2–4 words around the word at [index] (sin embargo, a pesar de),
     * as a range of token indices; words must be separated by single spaces. Null if none.
     */
    override fun phraseAt(tokens: List<RToken>, text: String, index: Int): IntRange? {
        if (tokens.getOrNull(index)?.kind != TokenKind.HAN) return null
        // Word tokens reachable from [index] through single spaces, up to three on each side.
        fun step(from: Int, dir: Int): Int? {
            val sp = tokens.getOrNull(from + dir) ?: return null
            if (sp.kind != TokenKind.SPACE || sp.text != " ") return null
            return (from + 2 * dir).takeIf { tokens.getOrNull(it)?.kind == TokenKind.HAN }
        }
        val left = generateSequence(index) { step(it, -1) }.take(4).toList()
        val right = generateSequence(index) { step(it, 1) }.take(4).toList()
        // The longest phrase; a reflexive one after a reflexive pronoun wins over a longer plain one
        // (me di cuenta de: darse cuenta, not dar cuenta de «отчитываться»).
        var best: IntRange? = null
        var bestScore = 0
        for (a in left) for (b in right) {
            val words = (b - a) / 2 + 1
            if (words < 2 || words > 4) continue
            val key = phraseKey(tokens, text, a, b)?.takeIf { usefulPhrase(it) } ?: continue
            // «volver a» is «снова» only before an infinitive (volvió a llamar), not in «volvió a casa».
            // A verb + preposition after an article is a noun + preposition: «el tratado de» «договор», not tratar de.
            if (a > 1 && tokens[a - 1].text == " " && tokens[a - 2].text.lowercase() in NOUN_MARKERS && entry(key)?.pos == "гл.") continue
            if (key in BEFORE_INFINITIVE && !(tokens.getOrNull(b + 1)?.text == " " && isInfinitive(tokens.getOrNull(b + 2)?.text))) continue
            val reflexiveKey = reflexiveBefore(tokens, a) && key.substringBefore(' ').endsWith("se")
            val score = words + if (reflexiveKey) 10 else 0
            if (score > bestScore) { best = a..b; bestScore = score }
        }
        return best
    }

    /** A third-person indicative form of a verb (enseña, cuentan), not a subjunctive, imperative or participle. */
    private fun finiteThird(a: EsAnalysis): Boolean =
        a.entry.pos == "гл." && a.grammar.startsWith("3 л.") && listOf("субх.", "повел.", "причастие").none { it in a.grammar }

    private fun isInfinitive(word: String?): Boolean =
        word != null && analyses(word).any { it.entry.pos == "гл." && (it.grammar.startsWith("инфинитив") || it.entry.lemma == word.lowercase()) }

    /** The dictionary word of [word] (hablo → hablar); the word itself, lower-case, if unknown. */
    override fun lemma(word: String): String = analyses(word).firstOrNull()?.entry?.lemma ?: word.lowercase()

    override fun chapter(paragraphs: List<String>, options: TextOptions): (String) -> PreparedText = { text ->
        PreparedText(text, text, inContext(tokenize(text), text))
    }

    /**
     * Readings the neighbours decide, kept in [RToken.pinyin] (never drawn for Spanish):
     * - after a pronoun that goes with a verb (se, me, te…), a subject pronoun (yo, tú, él…) or «no»,
     *   a word that can be a verb is the verb: «se alza» alzar, «no como» comer (not «как»);
     * - a form of both ser and ir before «a»/«al» is ir: «fue a Madrid», but «fue profesor» stays ser;
     * - after a form of haber a participle is the verb: «había adoptado» adoptar, not «приёмный»;
     * - after an article or a possessive, a verb that is also a noun is the noun: «el poder» «власть»
     *   (the reading is then the noun's [EsEntry.key], "poder/сущ.").
     * With [text], dictionary phrases are marked too: the first word carries the phrase
     * («trajeron cola»: "traer cola"), the following ones [IN_PHRASE], so the hint over the text is
     * the meaning of the whole phrase, not of each word.
     */
    fun inContext(tokens: List<RToken>, text: String? = null): List<RToken> {
        val words = tokens.indices.filter { tokens[it].kind == TokenKind.HAN }
        val out = tokens.toMutableList()
        for ((k, i) in words.withIndex()) {
            val t = tokens[i]
            // Neighbours only within the clause: punctuation between them breaks the link.
            fun linked(a: Int, b: Int) = (a + 1 until b).all { tokens[it].kind == TokenKind.SPACE }
            val before = words.getOrNull(k - 1)?.takeIf { linked(it, i) }?.let { tokens[it].text.lowercase() }
            val after = words.getOrNull(k + 1)?.takeIf { linked(i, it) }?.let { tokens[it].text.lowercase() }
            val a = analyses(t.text)
            if (a.size < 2) continue
            val choice = when {
                after in setOf("a", "al") && a.any { it.entry.lemma == "ir" } && a.any { it.entry.lemma == "ser" } -> "ir"
                // After haber only a participle: «había una casa» stays «una».
                before != null && before in HABER && a.first().entry.pos != "гл." ->
                    a.firstOrNull { it.entry.pos == "гл." && "причастие" in it.grammar }?.entry?.key
                before != null && (before in VERB_PRONOUNS || before in SUBJECT_PRONOUNS || before == "no") && a.first().entry.pos != "гл." ->
                    a.firstOrNull { it.entry.pos == "гл." }?.entry?.key
                // A capitalised word inside a sentence is a name (el Caribe): its part of speech is not guessed.
                t.text[0].isUpperCase() && k > 0 -> null
                // After an article or a possessive, a verb or adverb that is also a noun is the noun: el poder «власть».
                before != null && before in NOUN_MARKERS && a.first().entry.pos in setOf("гл.", "нар.") ->
                    a.firstOrNull { it.entry.pos == "сущ." && it.entry.ru.firstOrNull()?.startsWith("буква") != true }?.entry?.key
                // With no article or adjective before them, «mañana» is «завтра» and «tarde» «поздно»; «la mañana» is «утро».
                t.text.lowercase() in BARE_ADVERBS && a.first().entry.pos == "сущ." && !(before != null && (before in NOUN_MARKERS ||
                    before in setOf("de", "esta", "esa", "aquella") || analyses(before).firstOrNull()?.entry?.pos in setOf("прил.", "числ."))) ->
                    a.firstOrNull { it.entry.pos == "нар." }?.entry?.key
                // A noun that is also a verb form, with no article before it and an article or «toda» after it, is the
                // verb: «ahora abarca toda la República» abarcar, not «abarca» «сандалия».
                // Only a third-person indicative form, and not after an adjective or a numeral («gran cosa el», «ocho meses la»).
                a.first().entry.pos == "сущ." && (before == null || (before !in NOUN_MARKERS && before !in PREPOSITIONS_BEFORE_NOUN &&
                    analyses(before).firstOrNull()?.entry?.pos !in setOf("прил.", "числ.", "опред."))) &&
                    after != null && after in DETERMINERS_AFTER_VERB && a.any { finiteThird(it) } ->
                    a.firstOrNull { finiteThird(it) }?.entry?.key
                // After a word that is only a noun, a noun that is also an adjective is the adjective: «un oficial corrupto».
                before != null && a.first().entry.pos == "сущ." && a.any { it.entry.pos == "прил." } && before !in NOUN_MARKERS &&
                    analyses(before).let { b -> b.isNotEmpty() && b.all { it.entry.pos == "сущ." } } ->
                    a.firstOrNull { it.entry.pos == "прил." }?.entry?.key
                // After an article, a numeral or an adjective, with no noun following, an adjective that is also a noun
                // is the noun: «un joven llegó», «los ricos»; «el joven médico» stays an adjective.
                before != null && a.first().entry.pos == "прил." && a.any { it.entry.pos == "сущ." } && t.text.lowercase() !in ORDINALS &&
                    (before in NOUN_MARKERS || analyses(before).let { b -> b.isNotEmpty() && b.all { it.entry.pos in setOf("прил.", "числ.") } }) &&
                    (after == null || analyses(after).firstOrNull()?.entry?.pos != "сущ.") ->
                    a.firstOrNull { it.entry.pos == "сущ." }?.entry?.key
                else -> null
            }
            if (choice != null && choice != a.first().entry.key) out[i] = t.copy(pinyin = choice)
        }
        if (text != null) {
            var k = 0
            while (k < words.size) {
                val i = words[k]
                val r = phraseAt(tokens, text, i)
                if (r == null || r.first != i) { k++; continue }
                val key = phraseKey(tokens, text, r.first, r.last) ?: run { k++; continue }
                out[i] = out[i].copy(pinyin = key)
                for (j in r.first + 1..r.last) if (tokens[j].kind == TokenKind.HAN) out[j] = out[j].copy(pinyin = IN_PHRASE)
                while (k < words.size && words[k] <= r.last) k++
            }
        }
        // Verbs outside phrases: with a reflexive pronoun the pronominal verb (se limita: limitarse «ограничиваться»);
        // with its preposition after an adverb, the verb with that preposition (contamos actualmente con: contar con).
        for ((k, i) in words.withIndex()) {
            val t = out[i]
            if (t.pinyin == IN_PHRASE || ' ' in t.pinyin) continue
            val a = analyses(t.text)
            val v = (a.firstOrNull { it.entry.key == t.pinyin } ?: a.firstOrNull())?.takeIf { it.entry.pos == "гл." } ?: continue
            fun linked(x: Int, y: Int) = (x + 1 until y).all { tokens[it].kind == TokenKind.SPACE }
            val before = words.getOrNull(k - 1)?.takeIf { linked(it, i) }?.let { tokens[it].text.lowercase() }
            val lemma = v.entry.lemma
            val after = words.getOrNull(k + 1)?.takeIf { linked(i, it) }?.let { tokens[it].text.lowercase() }
            // «se dice que», «se señaló que»: impersonal, the plain verb.
            val reflexive = lemma !in NOT_PRONOMINAL && (v.grammar.endsWith("+ se") || (before == "se" && after != "que") ||
                (before == "me" && v.grammar.startsWith("1 л. ед. ч.")) || (before == "te" && v.grammar.startsWith("2 л. ед. ч.")) ||
                (before == "nos" && v.grammar.startsWith("1 л. мн. ч.")))
            val gap = words.getOrNull(k + 1)?.takeIf { linked(i, it) }
            val prep = words.getOrNull(k + 2)?.takeIf { gap != null && linked(gap, it) && tokens[gap].text.lowercase().let { g -> g.endsWith("mente") || g in GAP_ADVERBS } }
                ?.let { tokens[it].text.lowercase().let { p -> CONTRACTED[p] ?: p } }?.takeIf { it in VERB_PREPOSITIONS }
            // acabar / terminar + gerund: «acabaron cayendo» — in the end they fell.
            val gerund = after != null && words.getOrNull(k + 1)?.let { linked(i, it) } == true &&
                analyses(after).any { it.entry.pos == "гл." && it.grammar.startsWith("герундий") }
            val key = when {
                gerund && dict.contains("$lemma haciendo") -> "$lemma haciendo"
                prep != null && reflexive && dict.contains("${lemma}se $prep") -> "${lemma}se $prep"
                prep != null && dict.contains("$lemma $prep") && "$lemma $prep" !in BEFORE_INFINITIVE -> "$lemma $prep"
                reflexive && !lemma.endsWith("se") && entry(lemma + "se")?.ru?.isNotEmpty() == true -> lemma + "se"
                else -> null
            }
            if (key != null) out[i] = t.copy(pinyin = key)
        }
        if (!rules.isEmpty) chooseMeanings(out, words)
        return out
    }

    /**
     * Meanings the sentence chooses ([rules]): the reading becomes "key#meaning" («durmiente#шпала»), so
     * the hint, the card and the summary show that meaning. Words of a phrase are left to the phrase.
     */
    private fun chooseMeanings(out: MutableList<RToken>, words: List<Int>) {
        var start = 0
        while (start < words.size) {
            // A sentence: words up to a full stop, a question or an exclamation mark.
            var end = start
            while (end + 1 < words.size && (words[end] + 1 until words[end + 1]).none { j -> out[j].kind == TokenKind.PUNCT && out[j].text.any { it in ".!?…;" } }) end++
            val idx = words.subList(start, end + 1)
            val lemmasOf = HashMap<Int, Set<String>>()
            fun lemmas(k: Int) = lemmasOf.getOrPut(k) { analyses(out[idx[k]].text).mapTo(HashSet()) { it.entry.lemma } }
            val sentence = com.hanreader.app.lang.ContextRules.Sentence(
                idx.map { out[it].text.lowercase() }, ::lemmas,
                { k -> val t = out[idx[k]]; (analyses(t.text).firstOrNull { it.entry.key == t.pinyin } ?: analyses(t.text).firstOrNull())?.entry?.pos == "гл." })
            for (k in idx.indices) {
                val t = out[idx[k]]
                if (t.pinyin == IN_PHRASE || ' ' in t.pinyin) continue
                val own = lemmas(k)
                if (!rules.covers(own)) continue
                val meaning = rules.meaning(own, sentence, k) ?: continue
                val key = t.pinyin.ifEmpty { analyses(t.text).firstOrNull()?.entry?.key.orEmpty() }
                out[idx[k]] = t.copy(pinyin = "$key#$meaning")
            }
            start = end + 1
        }
    }

    /**
     * Whether a dictionary phrase is worth showing as one: Wiktionary also lists ordinary word
     * pairs with a rare sense («de la» «предлог родительного падежа», «la vida» «проституция»).
     * Left out: phrases ending in an article or possessive (de la, que su), starting with one
     * (la vida, una de), made only of small words unless a known idiom (por qué, sobre todo), and
     * ser / estar / hacer + preposition.
     */
    fun usefulPhrase(key: String): Boolean {
        if (key in PHRASE_IDIOMS) return true
        if (key in PHRASE_NOISE) return false
        val w = key.split(' ')
        if (w.size < 2) return false
        if (w.last() in DETERMINERS) return false
        if (w.first() in DETERMINERS) return false
        if (w.all { it in SMALL_WORDS }) return false
        if (w.size == 2 && w[0] in setOf("ser", "estar", "hacer", "es", "era", "fue") && w[1] in SMALL_WORDS) return false
        return true
    }

    private fun reflexiveBefore(tokens: List<RToken>, a: Int): Boolean =
        tokens.getOrNull(a - 1)?.text == " " && tokens.getOrNull(a - 2)?.text?.lowercase() in REFLEXIVE

    /**
     * The dictionary phrase written by tokens [a]..[b] (words separated by single spaces), also when
     * its first word is inflected or it is reflexive: «me di cuenta» is darse cuenta, «echo de menos»
     * echar de menos. Null if the dictionary has no such phrase.
     */
    override fun phraseKey(tokens: List<RToken>, text: String, a: Int, b: Int): String? {
        if (b <= a || tokens.getOrNull(a)?.kind != TokenKind.HAN || tokens.getOrNull(b)?.kind != TokenKind.HAN) return null
        for (j in a + 1..b) if (tokens[j].kind == TokenKind.SPACE && tokens[j].text != " ") return null
        // A phrase ending in «de» / «a» written with the article joined: «a pesar del» is a pesar de + el.
        val tail = CONTRACTED[tokens[b].text.lowercase()]
        fun from(i: Int) = if (tail == null) text.substring(i, tokens[b].end).lowercase() else text.substring(i, tokens[b].start).lowercase() + tail
        val surface = from(tokens[a].start)
        if (dict.contains(surface)) return surface
        val rest = from(tokens[a].end)
        val reflexive = reflexiveBefore(tokens, a)
        for (an in analyses(tokens[a].text)) {
            val lemma = an.entry.lemma
            // A reflexive pronoun before the verb: the reflexive phrase first (me di cuenta: darse cuenta).
            if ((reflexive || an.grammar.contains("+")) && dict.contains(lemma + "se" + rest)) return lemma + "se" + rest
            if (dict.contains(lemma + rest)) return lemma + rest
        }
        return null
    }

    /** The reading of [word] with dictionary form [reading] (chosen from the context), else the first. */
    /**
     * The entry of [word] read as [reading]: one of its own readings by [EsEntry.key], else the dictionary
     * word the context named (a phrase «traer cola», a pronominal verb «limitarse»), else the first reading.
     */
    private fun pick(word: String, readingInContext: String?): EsEntry? {
        val a = analyses(word)
        val reading = readingInContext?.let { com.hanreader.app.lang.ContextRules.key(it) }
        if (reading.isNullOrEmpty() || reading == IN_PHRASE) return a.firstOrNull()?.entry
        return a.firstOrNull { it.entry.key == reading }?.entry ?: entry(reading)?.takeIf { it.ru.isNotEmpty() } ?: a.firstOrNull()?.entry
    }

    fun tokenize(text: String): List<RToken> {
        val out = ArrayList<RToken>(text.length / 4 + 4)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val start = i
            val kind = when {
                c.isLetter() -> {
                    // A word may hold an apostrophe or a hyphen between letters (O'Donnell, franco-español).
                    while (i < text.length && (text[i].isLetter() || (text[i] in "'’-" && i + 1 < text.length && text[i + 1].isLetter() && i > start))) i++
                    TokenKind.HAN
                }
                c.isWhitespace() -> { while (i < text.length && text[i].isWhitespace()) i++; TokenKind.SPACE }
                c.isDigit() -> { while (i < text.length && text[i].isLetterOrDigit()) i++; TokenKind.OTHER }
                else -> { i++; TokenKind.PUNCT }
            }
            val t = text.substring(start, i)
            out += RToken(t, "", kind, start, i, kind == TokenKind.HAN && analyses(t).isNotEmpty())
        }
        return out
    }

    /** The variant chosen in «Оформление» (Spain, Latin America or none). */
    private val variant: EsVariant get() = com.hanreader.app.AppearanceStore.appearance.value.esVariant

    /** Russian senses with the chosen variant's regional ones first and other regions' last. */
    override fun senses(entry: EsEntry): List<String> = entry.ru.sortedBy { variant.rank(it) }

    override fun cardAnalyses(tokens: List<RToken>, text: String, a: Int, b: Int): List<EsAnalysis> {
        val word = text.substring(tokens[a].start, tokens[b].end)
        // Several words: a dictionary phrase, also inflected («me di cuenta» -> darse cuenta).
        val all = (if (a != b) phraseKey(tokens, text, a, b)?.let { analyses(it) } else null) ?: analyses(word)
        val inContext = if (a == b) com.hanreader.app.lang.ContextRules.key(tokens[a].pinyin) else ""
        // A pronominal verb the context named (se limita: limitarse) goes before the plain verb.
        val pronominal = if (inContext.endsWith("se") && ' ' !in inContext && all.none { it.entry.key == inContext })
            all.firstOrNull { it.entry.lemma + "se" == inContext }?.let { v -> entry(inContext)?.let { EsAnalysis(v.form, it, v.grammar) } }
        else null
        return listOfNotNull(pronominal) + if (inContext.isEmpty()) all else all.sortedBy { if (it.entry.key == inContext) 0 else 1 }
    }

    /** The plain verb of a pronominal one shown first (limitarse: limitar): what «Знаю» and the conjugation are about. */
    override fun cardLemma(analyses: List<EsAnalysis>): String? = analyses.firstOrNull()?.entry?.lemma?.let { l ->
        l.removeSuffix("se").takeIf { b -> l.endsWith("se") && analyses.any { it.entry.lemma == b } } ?: l
    }

    override fun formNote(tokens: List<RToken>, index: Int, analyses: List<EsAnalysis>) = EsGrammar.note(this, tokens, index, analyses)

    override fun conjugationTable(lemma: String): com.hanreader.app.lang.Conjugation? {
        val c = conjugation(lemma)
        if (c.isEmpty()) return null
        val persons = listOf("1 ед" to "yo", "2 ед" to "tú", "3 ед" to "él, ella, usted", "1 мн" to "nosotros", "2 мн" to "vosotros", "3 мн" to "ellos, ustedes")
        val nonFinite = listOf("герундий" to "Gerundio", "причастие" to "Participio").mapNotNull { (k, t) -> c[k]?.get("")?.let { "$t: ${it.joinToString(" / ")}" } }
        val tenses = CONJ_TENSES.filter { it != "герундий" && !it.contains("причастие") }.mapNotNull { tense ->
            val rows = c[tense] ?: return@mapNotNull null
            EsGrammar.spanishName(tense) to persons.mapNotNull { (key, who) -> rows[key]?.let { who to it.joinToString(" / ") } }
        }
        return com.hanreader.app.lang.Conjugation(nonFinite, tenses)
    }

    override fun glossShort(word: String, reading: String?): String? {
        if (reading == IN_PHRASE) return null
        // The meaning the sentence chose (durmientes near «rieles»: «шпала»).
        com.hanreader.app.lang.ContextRules.chosen(reading)?.let { return it }
        // The first word of a phrase: the meaning of the phrase (trajeron cola: «вызвать последствия»).
        val e = pick(word, reading) ?: return null
        // A name with no Russian meaning (Gustavo «a male given name»): no hint.
        if (e.pos == "имя" && e.ru.isEmpty()) return null
        // A sense of the chosen variant wins over the general main meaning (carro: «автомобиль» in Latin America).
        e.ru.firstOrNull { variant.rank(it) == 0 }?.let { s -> shortSense(s)?.let { return it } }
        if (e.short.isNotEmpty()) return e.short
        return (e.ru.firstOrNull() ?: e.en.firstOrNull()?.removePrefix("to "))?.let { shortSense(it) }
    }

    override fun cardTranslation(word: String, reading: String?): String? {
        val e = pick(word, reading) ?: return null
        val chosen = com.hanreader.app.lang.ContextRules.chosen(reading)
        val senses = senses(e).ifEmpty { e.en }
        // The meaning the sentence chose first, then the dictionary's main one.
        if (chosen != null) return (listOf(chosen) + senses.filter { it != chosen }).take(2).joinToString("; ")
        return senses.take(2).joinToString("; ").ifEmpty { null }
    }

    /**
     * Spanish writes only proper names with a capital: a capitalised word that does not start a
     * sentence (Rocosas, Nueva York) is a name, known to the dictionary or not.
     */
    override fun sureNames(tokens: List<RToken>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var sentenceStart = true
        for ((i, t) in tokens.withIndex()) {
            when (t.kind) {
                TokenKind.SPACE -> if (t.text.contains('\n')) sentenceStart = true
                TokenKind.HAN -> {
                    if (!sentenceStart && t.text[0].isUpperCase()) out += i..i
                    sentenceStart = false
                }
                TokenKind.PUNCT -> if (t.text[0] in ".!?¿¡…—«\"“:;") sentenceStart = true
                else -> sentenceStart = false
            }
        }
        return out
    }

    override fun studyable(word: String): Boolean = analyses(word).firstOrNull()?.entry?.isName == false

    /** A name of the dictionary (Sinaloa), or a capitalised word it does not know (Blancornelas). */
    override fun isName(word: String): Boolean {
        val a = analyses(word)
        if (a.isEmpty()) return word.firstOrNull()?.isUpperCase() == true
        return a.first().entry.isName
    }

    /**
     * Words counted by dictionary form (habló and hablaban as hablar); unknown words as written.
     * The book's names: words written with a capital inside a sentence and never in lower case
     * (Rocosas, Supermax) are stored as the book's names (ignored words), so they are names at the
     * start of a sentence too.
     */
    override fun analyze(bookId: Long, book: com.hanreader.app.epub.BookSource, onProgress: (Int, Int) -> Unit): com.hanreader.app.reader.BookAnalysis {
        val counts = HashMap<String, IntArray>()
        val capitalised = HashMap<String, IntArray>() // inside a sentence: [count, firstChapter]
        val lower = HashSet<String>()
        var tokens = 0
        for (c in book.chapters) {
            for (b in book.blocks(c.index)) {
                val text = (b as? com.hanreader.app.epub.EpubBlock.Text)?.text ?: continue
                val tk = tokenize(text)
                val names = sureNames(tk).mapTo(HashSet()) { it.first }
                for ((i, t) in tk.withIndex()) {
                    if (t.kind != TokenKind.HAN) continue
                    tokens++
                    if (t.text[0].isLowerCase()) lower += t.text
                    if (i in names) {
                        capitalised.getOrPut(t.text) { intArrayOf(0, c.index) }[0]++
                        continue
                    }
                    val key = analyses(t.text).firstOrNull()?.entry?.lemma ?: t.text
                    counts.getOrPut(key) { intArrayOf(0, c.index) }[0]++
                }
            }
            onProgress(c.index + 1, book.chapters.size)
        }
        val words = counts.map { (w, e) -> com.hanreader.app.BookWord(bookId, w, e[0], e[1]) }
        val names = capitalised.filterKeys { it.lowercase() !in lower }
            .map { (w, e) -> com.hanreader.app.BookWord(bookId, w, e[0], e[1], ignored = true) }
        // A word seen capitalised mid-sentence but also in lower case (Real, but also «real») is an ordinary word.
        val ordinary = capitalised.filterKeys { it.lowercase() in lower }
        val merged = words.toMutableList()
        for ((w, e) in ordinary) {
            val key = analyses(w).firstOrNull()?.entry?.lemma ?: w.lowercase()
            val i = merged.indexOfFirst { it.word == key }
            if (i >= 0) merged[i] = merged[i].copy(count = merged[i].count + e[0]) else merged += com.hanreader.app.BookWord(bookId, key, e[0], e[1])
        }
        return com.hanreader.app.reader.BookAnalysis(merged + names.filter { n -> merged.none { it.word == n.word } }, tokens)
    }

    /** A form counts as known when its dictionary word is known (habló, if hablar is known). */
    override fun knownByParts(word: String, known: Set<String>): Boolean =
        word.lowercase() in known || analyses(word).any { it.entry.lemma in known }

    /**
     * Levels A1–C2 by frequency, not an official list: the most frequent 500 words count as A1,
     * up to 1 500 A2, 3 000 B1, 5 000 B2, 8 000 C1, 16 000 C2 (ranks of FrequencyWords).
     */
    override val levels: com.hanreader.app.lang.WordLevels = object : com.hanreader.app.lang.WordLevels {
        private val bands = intArrayOf(500, 1_500, 3_000, 5_000, 8_000, 16_000)
        private val titles = listOf("A1", "A2", "B1", "B2", "C1", "C2")
        private val byLevel: List<Set<String>> by lazy {
            val sets = List(bands.size) { HashSet<String>() }
            dict.forEach { lemma, f ->
                val r = f.getOrNull(4)?.toIntOrNull() ?: 0
                if (r > 0) levelOf(r).takeIf { it > 0 }?.let { sets[it - 1] += lemma }
            }
            sets
        }
        private fun levelOf(rank: Int): Int = bands.indexOfFirst { rank <= it } + 1

        override val name = ""
        override val scaleTitle = "уровней A1–C2 (по частотности слов)"
        override val all = (1..bands.size).toList()
        override fun title(level: Int) = titles.getOrElse(level - 1) { "C2+" }
        override fun of(word: String): Int = analyses(word).firstOrNull()?.entry?.rank?.takeIf { it > 0 }?.let { levelOf(it) } ?: 0
        override fun estimate(word: String): Int = of(word).takeIf { it > 0 } ?: (bands.size + 1)
        override fun knownAt(level: Int): Set<String> = (1..level.coerceAtMost(bands.size)).flatMapTo(HashSet()) { byLevel[it - 1] }
    }

    /**
     * The conjugation of verb [lemma] from the forms table: tense label («наст.») -> person
     * («1 ед») -> forms («hablara / hablase»), in the order of the forms' grammar; plus «герундий»
     * and «причастие» under person «». Voseo forms are left out. Scans the table (in the background).
     */
    fun conjugation(lemma: String): Map<String, Map<String, List<String>>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
        val person = Regex("(\\d) л\\. (ед|мн)\\. ч\\.")
        forms.forEachWithItem("$lemma:") { form, f ->
            for (item in f.firstOrNull().orEmpty().removePrefix("!").split('|')) {
                if (item.substringBefore(':') != lemma) continue
                val g = item.substringAfter(':', "0").toIntOrNull()?.let { grammar.getOrNull(it) }.orEmpty()
                if (g.isEmpty() || "восео" in g || " + " in g) continue
                val parts = g.split(", ").map { it.trim() }
                val tense = parts.lastOrNull { it in CONJ_TENSES } ?: continue
                val key = when {
                    tense.contains("причастие") -> if ("ж. р." in g || "мн. ч." in g || !form.endsWith("o")) continue else "причастие"
                    // A gerund with a pronoun attached (hablándose) is not the gerund itself.
                    tense == "герундий" -> if (!form.endsWith("ndo")) continue else tense
                    else -> tense
                }
                val who = person.find(g)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: ""
                out.getOrPut(key) { LinkedHashMap() }.getOrPut(who) { ArrayList() }.let { if (form !in it) it += form }
            }
        }
        return out
    }

    /** Searched without case and stress marks: «cancion» finds «canción». */
    override fun searchForm(text: String): String = unaccent(text.lowercase())

    companion object {
        /** Tense and mood labels of the forms' grammar, in the order of a conjugation table. */
        val CONJ_TENSES = listOf("наст.", "претерит", "имперфект", "буд.", "условн.", "наст. субх.", "имперфект субх.",
            "буд. субх.", "повел.", "герундий", "причастие", "прош. причастие")

        private val PRONOUNS = listOf("nos", "los", "las", "les", "me", "te", "se", "os", "lo", "la", "le")

        /** Drops stress marks, keeping the length (ñ and ü stay). */
        fun unaccent(s: String): String = buildString(s.length) {
            for (c in s) append(when (c) { 'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú' -> 'u'
                'Á' -> 'A'; 'É' -> 'E'; 'Í' -> 'I'; 'Ó' -> 'O'; 'Ú' -> 'U'; else -> c })
        }

        /** The first part of a sense: brackets and everything after a comma or semicolon dropped. */
        fun shortSense(x: String): String? = x.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .trim().replace(LABELS, "")
            .split(',', ';').map { it.trim() }.firstOrNull { it.isNotEmpty() }

        /** Labels at the start of a sense: «геогр. пустыня», «разг. устар. …». */
        private val LABELS = Regex("^([а-яё]{2,12}\\.\\s*)+", RegexOption.IGNORE_CASE)

        /** Pronouns that stand before a verb: the next word is read as a verb if it can be one. */
        private val VERB_PRONOUNS = setOf("se", "me", "te", "nos", "os", "le", "les")
        private val SUBJECT_PRONOUNS = setOf("yo", "tú", "él", "ella", "usted", "nosotros", "nosotras", "vosotros", "vosotras", "ellos", "ellas", "ustedes", "vos")
        /** Words a noun follows, never a verb: after one «poder» is «власть». */
        /** Adverbs that may stand between a verb and its preposition (contamos ya con). */
        private val GAP_ADVERBS = setOf("ya", "también", "siempre", "aún", "todavía", "solo", "sólo", "nunca", "además", "hoy", "ahora", "más", "bien", "mucho", "apenas")
        private val VERB_PREPOSITIONS = setOf("a", "de", "con", "en", "por", "para")
        /** A preposition joined with the article: what it is in a phrase. */
        private val CONTRACTED = mapOf("del" to "de", "al" to "a")
        /** Verb + preposition that mean something else only before an infinitive: «volvió a llamar» «снова», not «volvió a casa». */
        private val BEFORE_INFINITIVE = setOf("volver a", "echar a", "echarse a", "ponerse a", "acabar de", "llegar a", "acabar por", "terminar por")
        /** Verbs whose forms after «se» are nearly always impersonal or passive (se está investigando, se tiene que). */
        private val NOT_PRONOMINAL = setOf("estar", "tener", "haber", "ser", "poder", "deber")
        /** Ordinals: «el segundo» is «второй», not «секунда». */
        private val ORDINALS = setOf("primero", "primera", "segundo", "segunda", "tercero", "tercera", "último", "última")
        /** Words a noun follows after a preposition: «con abarcas». */
        private val PREPOSITIONS_BEFORE_NOUN = setOf("de", "con", "en", "por", "para", "sin", "a", "y", "o", "muy", "más", "tan", "como")
        /** Words a verb is followed by more often than a noun: «abarca toda la República», «come el pan». */
        private val DETERMINERS_AFTER_VERB = setOf("el", "la", "los", "las", "toda", "todo", "todos", "todas", "su", "sus", "un", "una")
        private val BARE_ADVERBS = setOf("mañana", "tarde")
        private val NOUN_MARKERS = setOf("el", "la", "los", "las", "una", "un", "del", "su", "sus", "mi", "mis", "tu", "tus", "este", "ese", "aquel",
            "estos", "esos", "nuestro", "nuestra", "nuestros", "nuestras", "unos", "unas", "cada", "ningún", "algún")
        /** [RToken.pinyin] of a word inside a phrase after its first word: no hint of its own. */
        const val IN_PHRASE = com.hanreader.app.lang.IN_PHRASE
        /** Forms of haber: a participle after one is a verb (había adoptado). */
        private val HABER = setOf("he", "has", "ha", "hemos", "habéis", "han", "había", "habías", "habíamos", "habíais", "habían",
            "hube", "hubo", "hubimos", "hubieron", "habré", "habrá", "habremos", "habrán", "habría", "habrías", "habríamos", "habrían",
            "haya", "hayas", "hayamos", "hayan", "hubiera", "hubieras", "hubiéramos", "hubieran", "hubiese", "hubiesen", "haber", "habiendo")
        /** Articles, possessives and demonstratives: a phrase does not start or end with one. */
        private val DETERMINERS = setOf("el", "la", "los", "las", "lo", "un", "una", "unos", "unas", "su", "sus", "mi", "mis",
            "tu", "tus", "este", "esta", "estos", "estas", "ese", "esa", "esos", "esas", "aquel", "aquella", "nuestro", "nuestra")
        /** The most frequent small words: a phrase of only these is ordinary text unless a known idiom. */
        private val SMALL_WORDS = DETERMINERS + setOf("de", "a", "al", "del", "en", "y", "e", "o", "u", "que", "se", "si", "no", "ya",
            "más", "menos", "por", "para", "con", "sin", "sobre", "como", "todo", "toda", "todos", "todas", "es", "eso", "esto",
            "así", "hasta", "mientras", "qué", "cual", "cuál", "ni", "le", "les", "me", "te", "nos", "mismo", "misma", "puede", "tan", "muy")
        /** Idioms made of small words. */
        private val PHRASE_IDIOMS = setOf("por qué", "para que", "ya que", "así que", "sobre todo", "por eso", "es que", "es más",
            "al menos", "como si", "así como", "mientras que", "hasta que", "sin embargo", "ya no", "si no", "por lo tanto",
            "a menos que", "lo que", "a la vez", "a lo mejor", "por lo menos", "a lo largo de", "por lo general", "con todo")
        /** Dictionary phrases that in running text are nearly always the plain words. */
        private val PHRASE_NOISE = setOf("al día", "de la casa", "caso de", "en masa", "de más", "y todo", "de todo", "por todo",
            "más que", "se puede", "y si", "ser como", "hacer de", "los más", "lo mismo", "la vida", "una de", "de esta",
            "nada de", "cosa de", "ir de")

        /** Reflexive pronouns: «me di cuenta» reads as darse cuenta. */
        private val REFLEXIVE = setOf("me", "te", "se", "nos", "os")

        fun load(dict: java.io.InputStream, forms: java.io.InputStream, grammar: java.io.InputStream, context: java.io.InputStream? = null): EsEngine {
            val t = System.nanoTime()
            val d = SortedTsv.read(dict)
            val f = SortedTsv.read(forms)
            val g = grammar.bufferedReader().use { it.readLines() }
            val r = context?.bufferedReader()?.useLines { com.hanreader.app.lang.ContextRules.parse(it) } ?: com.hanreader.app.lang.ContextRules.EMPTY
            return EsEngine(d, f, g, (System.nanoTime() - t) / 1_000_000, r)
        }

        /**
         * A dictionary file copied once from the APK (compressed there) into app storage, with its
         * line index, then mapped into memory: later starts read nothing. A new install or update
         * makes a fresh copy.
         */
        private fun mapped(app: Context, name: String): SortedTsv {
            val stamp = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            val dir = java.io.File(app.filesDir, "es-$stamp")
            if (!dir.isDirectory) {
                app.filesDir.listFiles()?.filter { it.name.startsWith("es-") }?.forEach { it.deleteRecursively() }
                dir.mkdirs()
            }
            val file = java.io.File(dir, name)
            if (!file.isFile || file.length() == 0L) {
                val tmp = java.io.File(dir, "$name.tmp")
                app.assets.open("es/$name").use { src -> tmp.outputStream().use { src.copyTo(it, 1 shl 16) } }
                tmp.renameTo(file)
            }
            return SortedTsv.map(file, java.io.File(dir, "$name.idx"))
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var loading: CompletableDeferred<EsEngine>? = null

        /** Lets the loaded dictionary go (another language is read now); the next [warmUp] loads it again. */
        fun release() { synchronized(this) { loading = null } }

        /** Loads the dictionary once per process in the background (mapped from app storage). */
        fun warmUp(context: Context): CompletableDeferred<EsEngine> {
            loading?.let { return it }
            synchronized(this) {
                loading?.let { return it }
                val d = CompletableDeferred<EsEngine>()
                loading = d
                val app = context.applicationContext
                scope.launch {
                    runCatching {
                        val t = System.nanoTime()
                        val dict = mapped(app, "dict.tsv")
                        val forms = mapped(app, "forms.tsv")
                        val grammar = app.assets.open("es/grammar.tsv").bufferedReader().use { it.readLines() }
                        val rules = runCatching { app.assets.open("es/context.tsv").bufferedReader().useLines { com.hanreader.app.lang.ContextRules.parse(it) } }
                            .getOrDefault(com.hanreader.app.lang.ContextRules.EMPTY)
                        EsEngine(dict, forms, grammar, (System.nanoTime() - t) / 1_000_000, rules)
                    }.fold({ d.complete(it) }, { d.completeExceptionally(it) })
                }
                return d
            }
        }
    }
}
