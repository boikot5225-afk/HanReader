package com.hanreader.app.en

import android.content.Context
import com.hanreader.app.es.SortedTsv
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class EnTranslation(val sense: String, val words: List<String>)
data class EnPronunciation(val ipa: String, val labels: List<String>)

data class EnEntry(
    val word: String,
    val pos: String,
    val nativeRussian: List<String>,
    val english: List<String>,
    val translations: List<EnTranslation>,
    val pronunciations: List<EnPronunciation>,
    val rank: Int,
    val caseSensitive: Boolean
) {
    val russian: List<String> get() = nativeRussian.ifEmpty { translations.flatMap { it.words }.distinct() }
    val russianSource: String? get() = when {
        nativeRussian.isNotEmpty() -> "Русский Викисловарь"
        translations.isNotEmpty() -> "Английский Викисловарь: русские переводы"
        else -> null
    }
}

data class EnAnalysis(val form: String, val entry: EnEntry, val tags: Set<String>)

data class EnLookup(
    val form: EnSurfaceForm,
    val analyses: List<EnAnalysis>,
    val possessiveAnalyses: List<EnAnalysis>
)

/** POS-specific dictionary, attested forms and explicit surface grammar; no contextual guessing. */
class EnDictionary private constructor(private val words: SortedTsv, private val forms: SortedTsv,
    val rankedWords: Map<String, Int> = emptyMap()) {
    private data class Link(val lemma: String, val pos: String, val tags: Set<String>, val exact: String)

    private fun <T> cache() = object : LinkedHashMap<String, T>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > 2_000
    }
    private val wordCache = cache<List<EnEntry>>()
    private val formCache = cache<List<Link>>()

    private fun entries(key: String): List<EnEntry> = synchronized(wordCache) {
        wordCache.getOrPut(key) {
            words.get(key)?.firstOrNull()?.let { json ->
                JSONArray(json).objects().map { e ->
                    EnEntry(e.getString("w"), e.getString("p"), e.getJSONArray("r").strings(),
                        e.getJSONArray("e").strings(), e.getJSONArray("t").objects().map {
                            EnTranslation(it.getString("g"), it.getJSONArray("r").strings())
                        }, e.getJSONArray("i").objects().map {
                            EnPronunciation(it.getString("v"), it.getJSONArray("t").strings())
                        }, e.optInt("f"), e.optBoolean("c"))
                }
            }.orEmpty()
        }
    }

    private fun links(key: String): List<Link> = synchronized(formCache) {
        formCache.getOrPut(key) {
            forms.get(key)?.firstOrNull()?.let { json ->
                JSONArray(json).objects().map {
                    Link(it.getString("l"), it.getString("p"), it.getJSONArray("t").strings().toSet(), it.optString("c"))
                }
            }.orEmpty()
        }
    }

    private fun resolve(key: String, pos: String, seen: Set<String>, exactCase: Boolean = false): List<EnEntry> {
        if (key in seen || seen.size >= 6) return emptyList()
        val own = entries(key).filter { it.pos == pos && (!it.caseSensitive || exactCase) }
        if (own.isNotEmpty()) return own
        return links(key).filter { it.pos == pos && it.exact.isEmpty() }.flatMap { resolve(it.lemma, pos, seen + key) }
            .distinctBy { it.word to it.pos }
    }

    fun analyses(word: String): List<EnAnalysis> {
        val raw = EnForms.apostrophes(word)
        val key = EnForms.key(raw)
        val own = entries(key).filter { !it.caseSensitive || it.word == raw }
            .map { EnAnalysis(raw, it, emptySet()) }
        val inflected = links(key).filter { it.exact.isEmpty() || it.exact == raw }.flatMap { link ->
            resolve(link.lemma, link.pos, setOf(key), link.exact.isNotEmpty()).map { EnAnalysis(raw, it, link.tags) }
        }
        // A Russian definition is useful first, but alternatives are retained.
        return (own + inflected).distinctBy { Triple(it.entry.word, it.entry.pos, it.tags) }
            .sortedBy { if (it.entry.russian.isEmpty()) 1 else 0 }
    }

    fun lookup(word: String): EnLookup {
        val parsed = EnForms.parse(word)
        val base = parsed.possessive?.let { analyses(it.base) }.orEmpty()
        val plural = base.isNotEmpty() && base.all { "plural" in it.tags }
        val form = if (plural) parsed.copy(expansions = emptyList()) else parsed
        return EnLookup(form, analyses(word), base)
    }

    fun contains(word: String): Boolean {
        val key = EnForms.key(word)
        return words.contains(key) || forms.contains(key)
    }

    /** Walks the compact file off the main thread, for levels and statistics. */
    fun forEachEntry(action: (EnEntry) -> Unit) {
        words.forEach { key, _ -> entries(key).forEach(action) }
    }

    /** Forms of one verb, scanned only when its table is requested. */
    fun verbForms(lemma: String): List<Pair<String, Set<String>>> {
        val key = EnForms.key(lemma)
        val needle = "\"l\":" + JSONObject.quote(key)
        val result = ArrayList<Pair<String, Set<String>>>()
        forms.forEach { surface, fields ->
            if (fields.firstOrNull()?.contains(needle) == true) {
                links(surface).filter { it.lemma == key && it.pos == "verb" && it.exact.isEmpty() }
                    .forEach { result += surface to it.tags }
            }
        }
        return result.distinct()
    }

    companion object {
        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

        private fun ranks(input: InputStream): Map<String, Int> = input.bufferedReader().useLines { lines ->
            buildMap { lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) line.substring(tab + 1).toIntOrNull()?.let { put(line.substring(0, tab), it) }
            } }
        }

        fun read(dictionary: InputStream, forms: InputStream, rankInput: InputStream? = null): EnDictionary =
            EnDictionary(SortedTsv.read(dictionary), SortedTsv.read(forms), rankInput?.let(::ranks).orEmpty())

        /** Uses the same mmap TSV reader as Spanish; call on a background thread. */
        @Synchronized
        fun load(context: Context): EnDictionary {
            val app = context.applicationContext
            val manifest = app.assets.open("en/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.getInt("format") == 2) { "Unsupported English dictionary format" }
            val stamp = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
            val directory = File(app.filesDir, "en-$stamp")
            if (!directory.isDirectory) {
                app.filesDir.listFiles()?.filter { it.name.matches(Regex("en-\\d+")) }?.forEach { it.deleteRecursively() }
                check(directory.mkdirs() || directory.isDirectory) { "Cannot create English dictionary directory" }
            }
            fun map(name: String): SortedTsv {
                val file = File(directory, name)
                if (!file.isFile || file.length() == 0L) {
                    val temporary = File(directory, "$name.tmp")
                    app.assets.open("en/$name").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    check(temporary.renameTo(file)) { "Cannot install English dictionary" }
                }
                return SortedTsv.map(file, File(directory, "$name.idx"))
            }
            return EnDictionary(map("dict.tsv"), map("forms.tsv"), ranks(app.assets.open("en/ranks.tsv")))
        }
    }
}
