package com.hanreader.app.wiki

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class WikiHit(val id: Long, val title: String, val snippet: String)
data class WikiArticle(val language: String, val id: Long, val title: String, val extract: String,
    val revision: Long, val fetchedAt: Long, val ambiguous: Boolean) {
    val url get() = "https://$language.wikipedia.org/?curid=$id"
    val revisionUrl get() = "https://$language.wikipedia.org/w/index.php?oldid=$revision"
    val historyUrl get() = "https://$language.wikipedia.org/w/index.php?curid=$id&action=history"
}

interface WikipediaSource {
    suspend fun search(language: String, query: String): List<WikiHit>
    suspend fun article(language: String, id: Long): WikiArticle
    suspend fun translation(article: WikiArticle, language: String): WikiArticle?
    suspend fun fullArticle(article: WikiArticle): WikiDocument = error("Full articles unavailable")
    suspend fun savedArticles(): List<WikiArticle> = emptyList()
    suspend fun saveArticle(document: WikiDocument) { error("Offline storage unavailable") }
    suspend fun removeSavedArticle(article: WikiArticle) { error("Offline storage unavailable") }
}

/** Only the selected/editable query is sent. No book text, cookies or API key. */
class WikipediaRepository(context: Context,
    private val fetch: suspend (String) -> String = { download(it) },
    private val now: () -> Long = System::currentTimeMillis
) : WikipediaSource {
    private val directory = File(context.noBackupFilesDir, "wikipedia-v1")
    private val savedDirectory = File(context.filesDir, "wikipedia-reading-v1")
    private val mutex = Mutex()
    companion object {
        val languages = mapOf("ru" to "Русский", "es" to "Испанский", "en" to "Английский", "zh" to "Китайский")
        private suspend fun download(url: String): String = withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "AmautaReader/${com.hanreader.app.BuildConfig.VERSION_NAME} (https://github.com/boikot5225-afk/HanReader)")
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (connection.responseCode != 200) error("Wikipedia unavailable")
                val out = java.io.ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        require(out.size() + n <= 2_000_000)
                        out.write(buffer, 0, n)
                    }
                }
                out.toString("UTF-8")
            } finally { connection.disconnect() }
        }
    }
    private suspend fun request(language: String, arguments: Map<String, String>): JSONObject {
        require(language in languages)
        val params = mapOf("action" to "query", "format" to "json", "formatversion" to "2", "maxlag" to "5") + arguments
        val encoded = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return JSONObject(fetch("https://$language.wikipedia.org/w/api.php?$encoded")).also { require(!it.has("error")) }
    }
    private fun file(key: String): File = File(directory,
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    private suspend fun <T> cached(key: String, parse: (JSONObject) -> T, load: suspend () -> JSONObject): T =
        withContext(Dispatchers.IO) { mutex.withLock {
            val file = file(key)
            val cached = runCatching { parse(JSONObject(file.readText())) }.getOrNull()
            if (cached != null) return@withLock cached
            val json = load().put("amautaFetchedAt", now())
            coroutineContext.ensureActive()
            val value = parse(json) // Invalid responses are never treated as a successful cache entry.
            coroutineContext.ensureActive()
            directory.mkdirs()
            val temp = File.createTempFile("wiki-", ".tmp", directory)
            try {
                temp.writeText(json.toString())
                check(temp.renameTo(file))
                var bytes = 0L
                directory.listFiles()?.filter { it.extension == "json" }
                    ?.sortedWith(compareByDescending<File> { it == file }.thenByDescending { it.lastModified() })
                    ?.forEachIndexed { i, entry ->
                        if (i >= 100 || bytes + entry.length() > 20L * 1024 * 1024) entry.delete() else bytes += entry.length()
                    }
            } finally { temp.delete() }
            value
        } }
    override suspend fun search(language: String, query: String): List<WikiHit> {
        require(language in languages)
        val q = query.trim(); require(q.isNotBlank() && q.length <= 200)
        return cached("search:$language:$q", { json ->
            val rows = json.getJSONObject("query").getJSONArray("search")
            List(rows.length().coerceAtMost(8)) { i -> rows.getJSONObject(i).let {
                val id = it.getLong("pageid"); val title = it.getString("title")
                require(id > 0 && title.isNotBlank())
                WikiHit(id, title.take(300), Jsoup.parse(it.optString("snippet")).text().take(600))
            } }
        }) { request(language, mapOf("list" to "search", "srsearch" to q, "srnamespace" to "0", "srlimit" to "8", "srprop" to "snippet")) }
    }
    private fun parseArticle(language: String, json: JSONObject): WikiArticle {
        val page = json.getJSONObject("query").getJSONArray("pages").getJSONObject(0)
        require(!page.has("missing"))
        val id = page.getLong("pageid"); val title = page.getString("title")
        val extract = page.getString("extract").trim()
        val revision = page.getJSONArray("revisions").getJSONObject(0).getLong("revid")
        require(id > 0 && revision > 0 && title.isNotBlank() && extract.isNotBlank())
        return WikiArticle(language, id, title.take(300), extract.take(12_000), revision,
            json.getLong("amautaFetchedAt"), page.optJSONObject("pageprops")?.has("disambiguation") == true)
    }
    private suspend fun articleBy(language: String, field: String, value: String): WikiArticle =
        cached("article:$language:$field:$value", { parseArticle(language, it) }) {
            request(language, mapOf(field to value, "prop" to "extracts|pageprops|revisions", "exintro" to "1", "explaintext" to "1", "rvprop" to "ids", "redirects" to "1"))
        }
    override suspend fun article(language: String, id: Long): WikiArticle {
        require(id > 0); return articleBy(language, "pageids", id.toString())
    }
    override suspend fun translation(article: WikiArticle, language: String): WikiArticle? {
        require(language in languages && article.language in languages)
        if (language == article.language) return article
        val title = cached("link:${article.language}:${article.id}:$language", { json ->
            val page = json.getJSONObject("query").getJSONArray("pages").getJSONObject(0)
            require(!page.has("missing"))
            page.optJSONArray("langlinks")?.optJSONObject(0)?.optString("title").orEmpty()
        }) { request(article.language, mapOf("pageids" to article.id.toString(), "prop" to "langlinks", "lllang" to language, "lllimit" to "1")) }
        return title.takeIf { it.isNotBlank() }?.let { articleBy(language, "titles", it) }
    }

    private fun savedFile(article: WikiArticle): File {
        WikiDocuments.validateArticle(article)
        return File(savedDirectory, "${article.language}-${article.id}-${article.revision}.json")
    }

    override suspend fun fullArticle(article: WikiArticle): WikiDocument {
        WikiDocuments.validateArticle(article)
        require(!article.ambiguous)
        val saved = withContext(Dispatchers.IO) { mutex.withLock {
            savedFile(article).takeIf { it.isFile }?.let { stored -> runCatching {
                require(stored.length() <= WikiDocuments.MAX_FILE_BYTES)
                WikiDocuments.decode(stored.readText()).also { doc ->
                    require(doc.article.language == article.language && doc.article.id == article.id && doc.article.revision == article.revision)
                }
            }.getOrNull() }
        } }
        if (saved != null) return saved
        return cached("full-v1:${article.language}:${article.id}:${article.revision}", { json ->
            WikiDocuments.parse(article, json)
        }) { request(article.language, mapOf("action" to "parse", "oldid" to article.revision.toString(),
            "prop" to "text|revid", "disableeditsection" to "1", "disabletoc" to "1", "disablelimitreport" to "1")) }
    }

    override suspend fun savedArticles(): List<WikiArticle> = withContext(Dispatchers.IO) { mutex.withLock {
        savedDirectory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching {
                require(file.length() <= WikiDocuments.MAX_FILE_BYTES)
                WikiDocuments.decode(file.readText()).article.also { require(savedFile(it) == file) }
            }.getOrNull()
        }.sortedByDescending { it.fetchedAt }
    } }

    override suspend fun saveArticle(document: WikiDocument) = withContext(Dispatchers.IO) { mutex.withLock {
        val text = WikiDocuments.encode(document)
        val target = savedFile(document.article)
        savedDirectory.mkdirs()
        val files = savedDirectory.listFiles().orEmpty().filter { it.extension == "json" && it != target }
        check(files.size < 30 && files.sumOf { it.length() } + text.toByteArray().size <= 20L * 1024 * 1024) {
            "Освободи место: удали ненужные сохранённые статьи"
        }
        val temp = File.createTempFile("wiki-", ".tmp", savedDirectory)
        try { temp.writeText(text); check(temp.renameTo(target)) } finally { temp.delete() }
        Unit
    } }

    override suspend fun removeSavedArticle(article: WikiArticle) = withContext(Dispatchers.IO) { mutex.withLock {
        val stored = savedFile(article)
        check(!stored.exists() || stored.delete())
        // Remove the disposable snapshot too: deleting a saved article frees its cached text.
        val cache = file("full-v1:${article.language}:${article.id}:${article.revision}")
        if (cache.exists()) check(cache.delete())
        Unit
    } }
}
