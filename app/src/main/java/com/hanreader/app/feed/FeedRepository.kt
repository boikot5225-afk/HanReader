package com.hanreader.app.feed

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class FeedRefresh(val failed: List<FeedSource>, val attempted: Int)

/**
 * News feed from open sources only. The only requests are plain HTTPS GETs to the sources the user
 * selected (language + topic); no book text, cookies or keys are sent. Items live in a disposable cache.
 */
class FeedRepository(
    context: Context,
    private val fetch: suspend (String) -> String = { download(it) },
    private val now: () -> Long = System::currentTimeMillis
) {
    private val directory = File(context.noBackupFilesDir, "feed-v1")
    private val cacheFile = File(directory, "items.json")
    private val prefs = context.applicationContext.getSharedPreferences("feed-v1", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    companion object {
        private const val MAX_BYTES = 1_500_000
        private const val MAX_READ_MARKS = 2000

        private suspend fun download(start: String): String = withContext(Dispatchers.IO) {
            var url = start
            for (attempt in 0..3) {
                require(url.startsWith("https://"))
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "AmautaReader/${com.hanreader.app.BuildConfig.VERSION_NAME} (https://github.com/boikot5225-afk/HanReader)")
                connection.setRequestProperty("Accept", "application/json, application/rss+xml, application/atom+xml, application/xml, text/xml")
                try {
                    val code = connection.responseCode
                    if (code in 301..308 && code != 304) {
                        val next = connection.getHeaderField("Location") ?: error("Redirect without target")
                        url = URL(URL(url), next).toString()
                        continue
                    }
                    if (code != 200) error("Feed unavailable")
                    val out = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            require(out.size() + n <= MAX_BYTES)
                            out.write(buffer, 0, n)
                        }
                    }
                    return@withContext out.toString("UTF-8")
                } finally { connection.disconnect() }
            }
            error("Too many redirects")
        }
    }

    fun settings(defaultLanguage: String): FeedSettings {
        val known = FeedCatalog.languages()
        val languages = prefs.getStringSet("languages", null)?.filter { it in known }?.toSet()?.takeIf { it.isNotEmpty() }
            ?: setOf(defaultLanguage.takeIf { it in known } ?: known.first())
        val topics = prefs.getStringSet("topics", null)?.mapNotNull { n -> FeedTopic.values().firstOrNull { it.name == n } }?.toSet()
            ?.takeIf { it.isNotEmpty() } ?: FeedTopic.values().toSet()
        return FeedSettings(languages, topics, prefs.getBoolean("wifiOnly", false))
    }

    fun save(settings: FeedSettings) {
        prefs.edit().putStringSet("languages", settings.languages).putStringSet("topics", settings.topics.map { it.name }.toSet())
            .putBoolean("wifiOnly", settings.wifiOnly).apply()
    }

    fun readIds(): Set<String> = prefs.getStringSet("read", emptySet()).orEmpty().toSet()

    fun markRead(id: String) {
        val list = (prefs.getString("readOrder", "").orEmpty().split(',').filter { it.isNotEmpty() } - id + id).takeLast(MAX_READ_MARKS)
        prefs.edit().putString("readOrder", list.joinToString(",")).putStringSet("read", list.toSet()).apply()
    }

    suspend fun cached(settings: FeedSettings): List<FeedItem> =
        FeedItems.select(load().values.flatten(), settings, now())

    suspend fun clear() { withContext(Dispatchers.IO) { mutex.withLock { cacheFile.delete() } } }

    /** Refreshes only the sources the settings select. One failing source never hides the others. */
    suspend fun refresh(settings: FeedSettings): FeedRefresh {
        val wanted = FeedCatalog.sources.filter { it.language in settings.languages && it.topic in settings.topics }
        val gate = Semaphore(4)
        val results = coroutineScope {
            wanted.map { source -> async {
                gate.withPermit {
                    try { source to loadSource(source) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { source to null }
                }
            } }.awaitAll()
        }
        val fetched = results.mapNotNull { (s, items) -> items?.let { s.id to it } }.toMap()
        if (fetched.isNotEmpty()) mutex.withLock {
            val merged = load() + fetched
            store(FeedItems.prune(merged, now()))
        }
        return FeedRefresh(results.filter { it.second == null }.map { it.first }, wanted.size)
    }

    private suspend fun loadSource(source: FeedSource): List<FeedItem> {
        if (source.kind != FeedKind.WIKIPEDIA) return FeedParser.parse(source, fetch(source.url), now())
        // The «most read» list is complete for yesterday; fall back one more day if that is not published yet.
        var last: Exception? = null
        for (back in 1L..2L) {
            val day = DateTimeFormatter.ofPattern("yyyy/MM/dd").format(Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC).minusDays(back))
            try { return FeedParser.parse(source, fetch(source.url.replace("{date}", day)), now()).also { require(it.isNotEmpty()) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException()
    }

    private suspend fun load(): Map<String, List<FeedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(cacheFile.readText()).getJSONObject("sources")
            root.keys().asSequence().mapNotNull { id -> FeedCatalog.byId(id)?.let { id to items(root.getJSONArray(id)) } }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun items(array: JSONArray): List<FeedItem> = (0 until array.length()).mapNotNull { i ->
        runCatching {
            val o = array.getJSONObject(i)
            FeedItem(o.getString("id"), o.getString("source"), o.getString("lang"), FeedTopic.valueOf(o.getString("topic")),
                o.getString("name"), o.getString("title"), o.getString("summary"), o.getString("image"),
                o.getLong("at"), FeedItems.safeUrl(o.getString("url")).also { require(it.isNotEmpty()) }, o.optLong("wiki"))
        }.getOrNull()
    }

    private suspend fun store(map: Map<String, List<FeedItem>>) { withContext(Dispatchers.IO) {
        val sources = JSONObject()
        for ((id, list) in map) sources.put(id, JSONArray().also { a -> list.forEach {
            a.put(JSONObject().put("id", it.id).put("source", it.sourceId).put("lang", it.language).put("topic", it.topic.name)
                .put("name", it.sourceName).put("title", it.title).put("summary", it.summary).put("image", it.imageUrl)
                .put("at", it.publishedAt).put("url", it.url).put("wiki", it.wikiPageId))
        } })
        directory.mkdirs()
        val temp = File.createTempFile("feed-", ".tmp", directory)
        try { temp.writeText(JSONObject().put("sources", sources).toString()); check(temp.renameTo(cacheFile)) } finally { temp.delete() }
    } }
}
