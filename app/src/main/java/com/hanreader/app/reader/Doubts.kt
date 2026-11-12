package com.hanreader.app.reader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Translations the reader doubts: marked in the word card («Сомневаюсь в переводе»), gathered in
 * the library and sent as one message (to the developer, to a chat) to be checked and fixed in the
 * dictionary. Kept in the app's preferences, apart from the database: a short working list.
 */
object Doubts {
    data class Doubt(
        /** As on the page («dirigida»). */
        val word: String,
        /** The dictionary word or phrase the app read it as («dirigido», «dar cuenta de»). */
        val key: String,
        /** The meaning the app showed. */
        val shown: String,
        /** The sentence of the book. */
        val sentence: String,
        /** «El narco · 5. Magnates». */
        val source: String,
        val at: Long = System.currentTimeMillis()
    )

    private const val PREFS = "doubts"
    private const val KEY = "list"

    /** How many there are; -1 until the first read. */
    val count = MutableStateFlow(-1)

    fun all(context: Context): List<Doubt> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList<Doubt>().also { count.value = 0 }
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Doubt(o.optString("word"), o.optString("key"), o.optString("shown"), o.optString("sentence"), o.optString("source"), o.optLong("at"))
        }
        count.value = out.size
        return out
    }

    private fun save(context: Context, list: List<Doubt>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().put("word", d.word).put("key", d.key).put("shown", d.shown).put("sentence", d.sentence)
                .put("source", d.source).put("at", d.at))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        count.value = list.size
    }

    /** The same word in the same sentence is listed once. */
    fun has(context: Context, word: String, sentence: String): Boolean = all(context).any { it.word == word && it.sentence == sentence }

    fun add(context: Context, d: Doubt) {
        val list = all(context).filterNot { it.word == d.word && it.sentence == d.sentence }
        save(context, list + d)
    }

    fun remove(context: Context, word: String, sentence: String) = save(context, all(context).filterNot { it.word == word && it.sentence == sentence })

    fun clear(context: Context) = save(context, emptyList())

    /** One message with every doubt: what stood in the book, what the app showed, where. */
    fun message(appName: String, list: List<Doubt>): String = buildString {
        append(appName).append(": переводы под вопросом (").append(list.size).append(")\n")
        list.forEachIndexed { i, d ->
            append('\n').append(i + 1).append(". ").append(d.word)
            if (!d.key.equals(d.word, ignoreCase = true)) append(" → ").append(d.key)
            append(" — «").append(d.shown.ifBlank { "нет значения" }).append("»\n")
            if (d.sentence.isNotBlank()) append("   ").append(d.sentence.trim()).append('\n')
            if (d.source.isNotBlank()) append("   ").append(d.source).append('\n')
        }
    }
}
