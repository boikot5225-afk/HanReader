package com.hanreader.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One piece of the explained text: a quote from it, its reading (Chinese) and its meaning here. */
data class AiExplainPart(val text: String, val reading: String?, val meaning: String)

/** «Объясни предложение»: a translation, the text taken apart and its grammar. */
data class AiExplanation(val translation: String, val parts: List<AiExplainPart>, val grammar: String?)

/**
 * The request and the strict reading of its answer. Only the fragment, its paragraph and its
 * language are sent; the answer is data, never instructions, and every quote must be in the text.
 */
object AiExplain {
    const val MAX_TEXT = 1500
    private const val MAX_PARTS = 16

    fun payload(language: String, variant: String, text: String, paragraph: String, focus: String? = null): JSONObject? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_TEXT) return null
        return JSONObject().put("task", "explain").put("language", language).put("target_language", "ru")
            .put("regional_variant", variant).put("text", t).apply {
                if (paragraph.length <= AiContext.MAX_PARAGRAPH && paragraph.trim() != t) put("paragraph", paragraph)
                if (!focus.isNullOrBlank() && focus.trim() != t) put("focus", focus.trim())
            }
    }

    fun key(model: String, payload: JSONObject) = AiContext.hash(JSONArray(listOf("explain-v1", model, payload.toString())).toString())

    /** The answer for [text]; a part quoting something not in the text is dropped, not trusted. */
    fun parse(content: String, text: String): AiExplanation {
        val root = AiContext.strictObject(content)
        fun string(o: JSONObject, name: String, max: Int, required: Boolean): String? {
            val v = o.opt(name)
            if (v == null || v == JSONObject.NULL) { if (required) throw AiResponseFailure(AiResponseProblem.VALUE); return null }
            val s = (v as? String)?.trim() ?: throw AiResponseFailure(AiResponseProblem.FORMAT)
            if (s.length > max || (required && s.isEmpty())) throw AiResponseFailure(AiResponseProblem.VALUE)
            return s.ifEmpty { null }
        }
        val translation = string(root, "translation", 2000, true)!!
        val grammar = string(root, "grammar", 1200, false)
        val array = when (val p = root.opt("parts")) {
            null, JSONObject.NULL -> JSONArray()
            is JSONArray -> p
            else -> throw AiResponseFailure(AiResponseProblem.FORMAT)
        }
        val plain = text.normalized()
        val parts = (0 until minOf(array.length(), MAX_PARTS)).mapNotNull { i ->
            val o = array.opt(i) as? JSONObject ?: return@mapNotNull null
            try {
                val quote = string(o, "text", 200, true)!!
                if (quote.normalized() !in plain) null
                else AiExplainPart(quote, string(o, "reading", 200, false), string(o, "meaning", 300, true)!!)
            } catch (_: AiResponseFailure) { null }
        }
        return AiExplanation(translation, parts, grammar)
    }

    /** The cached form: the same shape the model returns. */
    fun json(e: AiExplanation): JSONObject = JSONObject().put("translation", e.translation)
        .put("parts", JSONArray().apply { e.parts.forEach { p ->
            put(JSONObject().put("text", p.text).put("meaning", p.meaning).apply { p.reading?.let { put("reading", it) } })
        } })
        .put("grammar", e.grammar ?: JSONObject.NULL)

    private fun String.normalized() = lowercase().replace(Regex("\\s+"), " ")
}

/** Explanations already paid for; [clear] goes with the AI cache. */
interface AiExplainCache { fun get(key: String): String?; fun put(key: String, value: String); fun clear() }

internal class MemoryExplainCache : AiExplainCache {
    private val map = LinkedHashMap<String, String>()
    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(key: String, value: String) { map[key] = value }
    @Synchronized override fun clear() = map.clear()
}

/** One small file per explanation in the app's private storage; the oldest go beyond [MAX]. */
internal class FileExplainCache(context: Context) : AiExplainCache {
    private val dir = File(context.noBackupFilesDir, "ai_explanations")
    private fun file(key: String) = File(dir, "$key.json")
    @Synchronized override fun get(key: String) = file(key).takeIf { it.isFile }?.readText()
    @Synchronized override fun put(key: String, value: String) {
        dir.mkdirs()
        file(key).writeText(value)
        val files = dir.listFiles() ?: return
        if (files.size > MAX) files.sortedBy { it.lastModified() }.take(files.size - MAX).forEach { it.delete() }
    }
    @Synchronized override fun clear() { dir.deleteRecursively() }
    private companion object { const val MAX = 500 }
}
