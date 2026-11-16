package com.hanreader.app.ai

import com.hanreader.app.lang.LanguageModule
import com.hanreader.app.lang.IN_PHRASE
import com.hanreader.app.reader.*
import com.hanreader.app.zh.TokenKind
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class AiTerm(val id: String, val original: String, val lemma: String, val base: String,
    val start: Int, val end: Int, val sentence: String, val reading: String? = null)
data class AiFragment(val book: Long, val chapter: Int, val block: Int, val resource: String,
    val text: String, val previous: String, val next: String, val variant: String,
    val manualHash: String, val terms: List<AiTerm>, val boundaries: Set<Int>,
    val protected: List<IntRange> = emptyList(), val language: String = "es-ES",
    val readings: Map<Int, String> = emptyMap()) {
    val contextHash: String get() = AiContext.hash(JSONArray(listOf(book, resource, chapter, block,
        text, previous, next, variant, manualHash, AiContext.rules(language))).toString())
    fun key(term: AiTerm, model: String): String {
        val parts = mutableListOf<Any>(contextHash, model, term.start, term.end, term.original, term.lemma, term.base)
        term.reading?.let { parts += it }
        return AiContext.hash(JSONArray(parts).toString())
    }
    fun json() = JSONObject().put("language", language).put("target_language", "ru").put("regional_variant", variant)
        .put("paragraph", text).put("previous_context", previous).put("next_context", next)
        .put("offsets", "UTF-16 code units, end exclusive, in paragraph")
        .put("protected_spans", JSONArray().apply { protected.forEach {
            put(JSONObject().put("start", it.first).put("end", it.last + 1))
        } })
        .put("terms", JSONArray().apply { terms.forEach { t -> put(JSONObject()
            .put("term_id", t.id).put("original", t.original).put("lemma", t.lemma)
            .put("current_translation", t.base).put("start", t.start).put("end", t.end)
            .put("sentence", t.sentence).apply {
                if (t.base.isBlank()) put("translation_missing", true)
                t.reading?.let { put("reading", it) }
            }) } })
}

/** Source positions, never page coordinates. Existing ContextMeanings keys retain their semantics. */
object AiContext {
    const val RULES = "es-context-1"
    /** Keep existing Spanish cache keys: enabling another language must not re-bill old checks. */
    fun rules(language: String) = if (language == "es-ES") RULES else "$language-context-1"
    fun supports(language: String) = language in setOf("es-ES", "en-US", "zh-CN")
    const val BATCH = 20
    const val MAX_PARAGRAPH = 6000
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun manual(book: Long, chapter: Int, block: Int, token: RToken, engine: LanguageModule,
               meanings: Map<String, String>): String? =
        meanings[ContextMeanings.here(book, chapter, block, token.start)]
            ?: meanings[ContextMeanings.inBook(book, ContextMeanings.keyOf(token, engine))]
            ?: meanings[token.text] ?: meanings[ContextMeanings.keyOf(token, engine)] ?: meanings[engine.lemma(token.text)]

    fun build(book: Long, chapter: ChapterContent, blockIndex: Int, resource: String, engine: LanguageModule,
              meanings: Map<String, String>, variant: String,
              glosses: Map<String, String>? = null, range: IntRange? = null,
              includeMissing: Boolean = false): AiFragment? {
        val block = chapter.blocks.getOrNull(blockIndex) as? ReaderBlock.Text ?: return null
        if (!supports(engine.info.tag)) return null
        if (block.heading || block.text.length > MAX_PARAGRAPH) return null
        val chinese = engine is ZhEngine
        val text = block.shown
        require(text.length == block.text.length)
        val words = block.tokens.filter { it.kind == TokenKind.HAN }
        val manualValues = sortedMapOf<String, String>()
        val protected = ArrayList<IntRange>()
        for (token in words) {
            val keys = listOf(ContextMeanings.here(book, chapter.index, blockIndex, token.start),
                ContextMeanings.inBook(book, ContextMeanings.keyOf(token, engine)), token.text,
                ContextMeanings.keyOf(token, engine), engine.lemma(token.text))
            for (key in keys) meanings[key]?.let { manualValues[key] = it }
            if (manual(book, chapter.index, blockIndex, token, engine, meanings) != null) protected += token.start until token.end
        }
        // Preserve manually selected expressions even if the local engine has not grouped them.
        for ((key, value) in meanings) {
            val raw = when {
                key.startsWith("@$book|b|") -> key.substringAfter("|b|")
                !key.startsWith("@") -> key
                else -> continue
            }
            if (raw.isBlank() || key in manualValues) continue
            for (match in Regex(Regex.escape(raw), RegexOption.IGNORE_CASE).findAll(block.text)) {
                if (words.any { it.start == match.range.first } && words.any { it.end == match.range.last + 1 }) {
                    manualValues[key] = value; protected += match.range
                }
            }
        }
        val manualHash = hash(JSONArray(manualValues.map { JSONArray(listOf(it.key, it.value)) }).toString())
        val terms = ArrayList<AiTerm>()
        for ((ti, token) in block.tokens.withIndex()) {
            if (token.kind != TokenKind.HAN || token.pinyin == IN_PHRASE) continue
            var end = token.end
            if (!chinese && ' ' in token.pinyin) {
                for (next in block.tokens.drop(ti + 1)) {
                    if (next.kind == TokenKind.HAN && next.pinyin != IN_PHRASE) break
                    if (next.kind == TokenKind.HAN) end = next.end
                }
            }
            if (range != null && (end <= range.first || token.start > range.last)) continue
            if (range != null) end = maxOf(end, range.last + 1)
            if (protected.any { it.first < end && it.last >= token.start }) continue
            // Unknown entries can be requested explicitly. Normal automatic candidate
            // selection still requires a dictionary hint; includeMissing is for saved overlays.
            val base = engine.glossShort(token)?.takeIf { it.isNotBlank() }
                ?: if (range != null || includeMissing) "" else continue
            if (glosses != null && glossOf(glosses, token, blockIndex).isNullOrBlank()) continue
            val original = text.substring(token.start, end)
            val key = ContextMeanings.keyOf(token, engine)
            // English token keys include /part-of-speech. Send the selected entry's actual lemma.
            val lemma = if (engine.info.tag == "en-US" && ' ' !in key && engine is com.hanreader.app.lang.AlphabeticModule)
                engine.cardAnalyses(block.tokens, block.text, ti, ti).firstOrNull()?.entry?.lemma ?: engine.lemma(token.text)
                else key
            val reading = if (chinese) words.filter { it.start >= token.start && it.end <= end }
                .map { it.pinyin }.filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() } else null
            terms += AiTerm("t_${token.start}_$end", original, lemma,
                base, token.start, end, ChapterBuilder.sentence(text, token.start, end), reading)
            if (range != null) break
        }
        if (terms.isEmpty()) return null
        fun neighbour(step: Int): String {
            var i = blockIndex + step
            while (i in chapter.blocks.indices) {
                val b = chapter.blocks[i] as? ReaderBlock.Text
                if (b != null && b.shown.isNotBlank()) return if (step < 0) b.shown.takeLast(700) else b.shown.take(700)
                i += step
            }
            return ""
        }
        return AiFragment(book, chapter.index, blockIndex, resource, text, neighbour(-1), neighbour(1),
            if (engine.info.tag == "es-ES") variant else "ANY", manualHash, terms,
            words.flatMap { listOf(it.start, it.end) }.toSet(), protected, engine.info.tag,
            if (chinese) words.associate { it.start to it.pinyin } else emptyMap())
    }

    /** All-or-nothing validation: malformed or incomplete packets never become successful checks. */
    fun validate(content: String, fragment: AiFragment, model: String): List<AiCheck> {
        require(content.length in 2..100_000)
        android.util.JsonReader(java.io.StringReader(content)).use { reader ->
            reader.isLenient = false
            reader.skipValue()
            require(reader.peek() == android.util.JsonToken.END_DOCUMENT)
        }
        val parser = org.json.JSONTokener(content)
        val root = parser.nextValue() as? JSONObject ?: error("JSON object required")
        require(parser.nextClean() == '\u0000')
        val results = root.getJSONArray("results")
        require(results.length() == fragment.terms.size)
        val terms = fragment.terms.associateBy { it.id }
        val seen = HashSet<String>()
        val rows = ArrayList<AiCheck>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val id = r.get("term_id") as? String ?: error("term_id")
            val t = terms[id] ?: error("Unknown term")
            require(seen.add(id))
            require(r.get("original") == t.original && r.get("current_translation") == t.base)
            val correct = r.get("needs_correction") as? Boolean ?: error("needs_correction")
            val confidence = r.get("confidence") as? String ?: error("confidence")
            require(confidence in setOf("high", "medium", "low"))
            require(r.has("suggested_translation") && r.has("explanation"))
            fun optional(name: String, limit: Int): String? {
                if (r.isNull(name)) return null
                val s = r.get(name) as? String ?: error(name)
                require(s.isNotBlank() && s.length <= limit && s.none { it.isISOControl() })
                return s.trim()
            }
            val translation = optional("suggested_translation", 100)
            val explanation = optional("explanation", 600)
            require(if (correct) translation != null else translation == null)
            var start = t.start
            var end = t.end
            if (r.has("span")) {
                val span = r.getJSONObject("span")
                require(span.get("start") is Int && span.get("end") is Int)
                start = span.getInt("start"); end = span.getInt("end")
                require(start <= t.start && end >= t.end && start >= 0 && end <= fragment.text.length && end > start)
                require(start in fragment.boundaries && end in fragment.boundaries)
                require(fragment.text.substring(start, end) == span.get("text"))
                require(end - start <= 180)
            }
            require(fragment.protected.none { it.first < end && it.last >= start })
            rows += AiCheck(fragment.key(t, model), fragment.book, fragment.chapter, fragment.block,
                fragment.contextHash, t.start, t.end, start, end, fragment.text.substring(start, end), t.base,
                translation, explanation, confidence, if (!correct) "checked" else if (confidence == "high") "accepted" else "proposed")
        }
        // Multiple terms can identify the same construction. Deduplicate consistent answers, reject conflicts.
        val accepted = rows.filter { it.translation != null }.sortedBy { it.start }
        for ((i, a) in accepted.withIndex()) for (b in accepted.drop(i + 1)) {
            if (b.start >= a.end) break
            require(a.start == b.start && a.end == b.end && a.translation == b.translation)
        }
        return rows
    }

    /** Keep only rows whose context, base value and model still match; manual values always win. */
    fun applicable(fragment: AiFragment, rows: List<AiCheck>, model: String): List<AiCheck> {
        return rows.filter { row ->
            val term = fragment.terms.firstOrNull { it.start == row.termStart }
            val reading = if (term?.reading != null && row.termEnd != term.end) fragment.readings
                .filterKeys { it >= row.termStart && it < row.termEnd }.toSortedMap().values.filter { it.isNotBlank() }.joinToString(" ")
                else term?.reading
            term != null && row.contextHash == fragment.contextHash && row.start in fragment.boundaries && row.end in fragment.boundaries && row.termEnd in term.end..fragment.text.length &&
                row.cacheKey == fragment.key(term.copy(end = row.termEnd,
                    original = fragment.text.substring(term.start, row.termEnd), reading = reading), model)
        }
    }

    fun overlay(glosses: MutableMap<String, String>, block: ReaderBlock.Text, blockIndex: Int,
                rows: List<AiCheck>, indicators: Boolean) {
        rows.filter { it.state == "accepted" && it.translation != null }
            .distinctBy { it.start to it.end }.forEach { row ->
                val tokens = block.tokens.filter { it.kind == TokenKind.HAN && it.start >= row.start && it.end <= row.end }
                tokens.forEachIndexed { i, t -> glosses["@$blockIndex:${t.start}"] =
                    if (i == 0) row.translation!! + if (indicators) " ·" else "" else "" }
            }
    }
}
