package com.hanreader.app.es

import com.hanreader.app.reader.RToken
import com.hanreader.app.zh.TokenKind

/** What a verb form is and why it is used, for the word card: offline, by rules. */
data class GrammarNote(
    /** Tense or mood in Spanish and Russian: «Pretérito indefinido — простое прошедшее». */
    val tense: String,
    /** «3-е лицо ед. ч. (él, ella, usted)»; null when the form has no person. */
    val person: String?,
    /** When it is used, in a sentence or two. */
    val explanation: String,
    /** The words of a compound form on the page: «había venido». Null for a single word. */
    val construction: String? = null,
    /** Pronouns attached to the verb: «me lo». */
    val pronouns: String? = null
)

object EsGrammar {
    private class Tense(val name: String, val explanation: String)

    private val SIMPLE = mapOf(
        "наст." to Tense("Presente de indicativo — настоящее время",
            "Действие сейчас, привычное или всегда верное: «trabajo en casa» — работаю дома. Бывает и о ближайшем будущем."),
        "претерит" to Tense("Pretérito indefinido — простое прошедшее",
            "Законченное действие в прошлом, часто с указанием когда: «ayer llegó» — вчера приехал."),
        "имперфект" to Tense("Pretérito imperfecto — прошедшее незавершённое",
            "Фон, описание, повторявшееся или длившееся действие в прошлом: «llovía» — шёл дождь, «iba cada día» — ходил каждый день."),
        "буд." to Tense("Futuro simple — будущее",
            "Будущее действие, а также догадка о настоящем: «serán las diez» — сейчас, наверное, десять."),
        "условн." to Tense("Condicional — условное наклонение",
            "Русское «бы»: возможное или желаемое, вежливая просьба, будущее в прошлом: «dijo que vendría» — сказал, что придёт."),
        "наст. субх." to Tense("Presente de subjuntivo — сослагательное, настоящее",
            "После que при желании, просьбе, сомнении, оценке, цели: «quiero que vengas» — хочу, чтобы ты пришёл; также отрицательные приказы: «no vengas»."),
        "имперфект субх." to Tense("Pretérito imperfecto de subjuntivo — сослагательное, прошедшее",
            "То же сослагательное в прошлом («quería que vinieras») и условие «если бы»: «si tuviera tiempo» — если бы у меня было время."),
        "буд. субх." to Tense("Futuro de subjuntivo — сослагательное, будущее",
            "Устаревшая форма: встречается в законах, поговорках и старых текстах."),
        "повел." to Tense("Imperativo — повелительное наклонение",
            "Просьба или приказ: «ven» — иди сюда, «dígame» — слушаю вас."),
        "причастие" to Tense("Participio — причастие",
            "С haber образует сложные времена («he visto» — я видел), с ser — страдательный залог; как прилагательное согласуется в роде и числе: «puerta cerrada»."),
        "прош. причастие" to Tense("Participio — причастие",
            "С haber образует сложные времена («he visto» — я видел), с ser — страдательный залог; как прилагательное согласуется в роде и числе: «puerta cerrada»."),
        "герундий" to Tense("Gerundio — деепричастие",
            "«Делая»: способ или одновременное действие; с estar — действие в процессе: «está lloviendo» — идёт дождь."),
        "инфинитив" to Tense("Infinitivo — начальная форма",
            "Неопределённая форма глагола: после другого глагола («quiero comer»), после предлога («antes de salir»), как существительное.")
    )

    /** haber + participle: the compound tense by the tense of haber. */
    private val COMPOUND = mapOf(
        "наст." to Tense("Pretérito perfecto — прошедшее, связанное с настоящим",
            "haber в настоящем + причастие: действие завершилось, но важно сейчас или в ещё не закончившийся период: «hoy he comido poco»."),
        "имперфект" to Tense("Pretérito pluscuamperfecto — предпрошедшее",
            "haber в имперфекте + причастие: действие случилось раньше другого прошлого: «cuando llegué, ya se había ido» — он уже ушёл."),
        "претерит" to Tense("Pretérito anterior — предпрошедшее (книжное)",
            "hubo + причастие: сразу после другого прошлого действия; встречается в литературе."),
        "буд." to Tense("Futuro perfecto — будущее завершённое",
            "habré + причастие: закончится к моменту в будущем, или догадка о прошлом: «habrá salido» — наверное, вышел."),
        "условн." to Tense("Condicional compuesto — условное прошедшее",
            "habría + причастие: «сделал бы» о прошлом, которое не случилось: «habría venido» — я бы пришёл."),
        "наст. субх." to Tense("Pretérito perfecto de subjuntivo — сослагательное, прошедшее",
            "haya + причастие: после que о завершённом действии: «espero que hayas llegado bien»."),
        "имперфект субх." to Tense("Pretérito pluscuamperfecto de subjuntivo — «если бы тогда»",
            "hubiera + причастие: нереальное прошлое: «si lo hubiera sabido, no habría venido» — если бы я знал, не пришёл бы.")
    )

    /** «Presente de indicativo» for a tense label of the forms («наст.»). */
    fun spanishName(label: String): String = SIMPLE[label]?.name?.substringBefore(" — ") ?: label

    private val PRONOUNS_OF = mapOf(
        "1 ед" to "yo", "2 ед" to "tú", "3 ед" to "él, ella, usted",
        "1 мн" to "nosotros", "2 мн" to "vosotros", "3 мн" to "ellos, ellas, ustedes"
    )

    private val PERSON = Regex("(\\d) л\\. (ед|мн)\\. ч\\.")

    /** The tense label of a grammar string: «3 л. ед. ч., претерит» -> «претерит». */
    private fun tenseOf(grammar: String): String? =
        grammar.substringBefore(" + ").split(", ").map { it.trim() }.lastOrNull { it in SIMPLE || it.startsWith("инфинитив") || it.startsWith("герундий") }
            ?.let { if (it.startsWith("инфинитив")) "инфинитив" else if (it.startsWith("герундий")) "герундий" else it }

    private fun personOf(grammar: String): String? = PERSON.find(grammar)?.let { m ->
        val (p, n) = m.destructured
        val who = PRONOUNS_OF["$p $n"]
        "$p-е лицо ${if (n == "ед") "ед." else "мн."} ч." + (who?.let { " ($it)" } ?: "") + (if ("восео" in grammar) ", восео (vos)" else "")
    }

    /**
     * The note for the word at [index], from its readings [all]: the first one; after haber, a
     * participle reading wins (hubiera sabido: saber, not the adjective sabido).
     */
    fun note(engine: EsEngine, tokens: List<RToken>, index: Int, all: List<EsAnalysis>): GrammarNote? {
        val prev = neighbour(tokens, index, -1)
        val afterHaber = prev != null && engine.analyses(tokens[prev].text).any { it.entry.lemma == "haber" }
        // Otherwise only the reading the card shows first: «la casa» is the noun, not a form of casar.
        if (!afterHaber) return all.firstOrNull()?.let { note(engine, tokens, index, it) }
        val ordered = all.sortedBy { if (tenseOf(it.grammar)?.contains("причастие") == true) 0 else 1 }
        for (a in ordered) note(engine, tokens, index, a)?.let { return it }
        return null
    }

    /**
     * The note for the word at [index] read as [a]. Null for words that are not verb forms.
     * Neighbours make compound forms: haber + participle, estar + gerund, ir a + infinitive.
     */
    fun note(engine: EsEngine, tokens: List<RToken>, index: Int, a: EsAnalysis): GrammarNote? {
        val isVerb = a.entry.pos == "гл."
        val grammar = a.grammar.ifEmpty { if (isVerb && a.form == a.entry.lemma) "инфинитив" else "" }
        val tense = tenseOf(grammar) ?: return null
        if (!isVerb && tense != "причастие" && tense != "прош. причастие") return null
        val pronouns = a.grammar.substringAfter(" + ", "").ifEmpty { null }
        val prev = neighbour(tokens, index, -1)
        val next = neighbour(tokens, index, 1)
        fun text(i: Int) = tokens[i].text
        fun lemmaAt(i: Int, lemma: String) = engine.analyses(text(i)).firstOrNull { it.entry.lemma == lemma }

        // haber + participle, from either word.
        if (tense == "причастие" || tense == "прош. причастие") {
            prev?.let { p -> lemmaAt(p, "haber")?.let { h -> compound(h, "${text(p)} ${text(index)}")?.let { return it } } }
        }
        if (a.entry.lemma == "haber" && next != null) {
            val part = engine.analyses(text(next)).firstOrNull { tenseOf(it.grammar)?.contains("причастие") == true }
            if (part != null) compound(a, "${text(index)} ${text(next)}")?.let { return it }
        }
        // estar + gerund: an action in progress.
        if (tense == "герундий" && prev != null && lemmaAt(prev, "estar") != null) {
            return GrammarNote("estar + gerundio — действие в процессе", null,
                "Русское «сейчас делает / делал тогда»: «estaba leyendo» — (как раз) читал.", "${text(prev)} ${text(index)}", pronouns)
        }
        if (a.entry.lemma == "estar" && next != null && engine.analyses(text(next)).any { tenseOf(it.grammar) == "герундий" }) {
            return GrammarNote("estar + gerundio — действие в процессе", personOf(grammar),
                "Русское «сейчас делает / делал тогда»: «estaba leyendo» — (как раз) читал.", "${text(index)} ${text(next)}")
        }
        // ir a + infinitive: the near future.
        if (a.entry.lemma == "ir" && next != null && text(next).lowercase() == "a") {
            val inf = neighbour(tokens, next, 1)
            if (inf != null && engine.analyses(text(inf)).any { it.entry.pos == "гл." && it.entry.lemma == text(inf).lowercase() }) {
                return GrammarNote("ir a + infinitivo — ближайшее будущее", personOf(grammar),
                    "«Собираюсь сделать»: «voy a comer» — сейчас поем; в прошедшем — «iba a salir» — собирался выйти.",
                    "${text(index)} a ${text(inf)}")
            }
        }
        val t = SIMPLE[tense] ?: return null
        return GrammarNote(t.name, personOf(grammar), t.explanation, null, pronouns)
    }

    private fun compound(haber: EsAnalysis, words: String): GrammarNote? {
        val t = COMPOUND[tenseOf(haber.grammar) ?: return null] ?: return null
        return GrammarNote(t.name, personOf(haber.grammar), t.explanation, words)
    }

    /** The word next to [index] in direction [dir], if only a space lies between them. */
    private fun neighbour(tokens: List<RToken>, index: Int, dir: Int): Int? {
        val sp = tokens.getOrNull(index + dir) ?: return null
        if (sp.kind != TokenKind.SPACE || sp.text != " ") return null
        return (index + 2 * dir).takeIf { tokens.getOrNull(it)?.kind == TokenKind.HAN }
    }
}
