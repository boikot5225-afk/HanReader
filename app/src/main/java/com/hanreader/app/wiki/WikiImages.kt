package com.hanreader.app.wiki

import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/** No remote HTML, SVG, cookies, redirects or third-party image hosts enter the reader. */
object WikiImages {
    const val MAX_BYTES = 3 * 1024 * 1024
    private val hosts = setOf("upload.wikimedia.org", "thumb.wikimedia.org")
    /** Current MediaWiki HTML and imageinfo append campaign tags; never forward them. */
    fun normalize(input: String): String? = runCatching {
        val u = URI(if (input.startsWith("//")) "https:$input" else input)
        require(u.scheme == "https" && u.host in hosts && u.rawUserInfo == null && u.port == -1 && u.rawFragment == null)
        require(u.rawQuery == null || u.rawQuery.split('&').all { it.substringBefore('=') in setOf("utm_source", "utm_campaign", "utm_content", "utm_medium") })
        "https://${u.host}${u.rawPath}".also { require(trusted(it)) }
    }.getOrNull()
    fun trusted(url: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme == "https" && u.host in hosts && u.rawUserInfo == null && u.port == -1 &&
            u.rawQuery == null && u.rawFragment == null && u.path.startsWith("/wikipedia/") &&
            u.path.split('/').none { it == ".." || it == "." } && url.length <= 2000
    }.getOrDefault(false)
    fun fileTitle(url: String): String? {
        if (!trusted(url)) return null
        return runCatching {
            val path = URI(url).rawPath.split('/')
            val encoded = if ("thumb" in path) path.getOrNull(path.lastIndex - 1) else path.lastOrNull()
            val name = URLDecoder.decode(requireNotNull(encoded).replace("+", "%2B"), "UTF-8").replace('_', ' ')
            require(name.isNotBlank() && name.length <= 300 && !name.contains('/') && !name.contains('\\'))
            "File:$name"
        }.getOrNull()
    }
    fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    internal fun checkBitmap(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..8000 && options.outHeight in 1..8000 && options.outWidth.toLong() * options.outHeight <= 20_000_000)
        require(options.outMimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif"))
    }
    internal suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        require(trusted(url))
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000; c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "AmautaReader/${com.hanreader.app.BuildConfig.VERSION_NAME} (https://github.com/boikot5225-afk/HanReader)")
        try {
            check(c.responseCode == 200)
            require(c.contentLengthLong <= MAX_BYTES)
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= MAX_BYTES); out.write(buffer, 0, n)
                }
            }
            out.toByteArray().also(::checkBitmap)
        } finally { c.disconnect() }
    }
    internal fun metadata(json: JSONObject): WikiImageInfo {
        val row = json.getJSONObject("query").getJSONArray("pages").getJSONObject(0).getJSONArray("imageinfo").getJSONObject(0)
        val url = requireNotNull(normalize(row.optString("thumburl").ifBlank { row.getString("url") }))
        val ext = row.optJSONObject("extmetadata") ?: JSONObject()
        fun text(name: String) = Jsoup.parse(ext.optJSONObject(name)?.optString("value").orEmpty()).text().take(1200)
        val page = row.getString("descriptionurl")
        val u = URI(page)
        require(u.scheme == "https" && (u.host == "commons.wikimedia.org" || u.host in WikipediaRepository.languages.keys.map { "$it.wikipedia.org" }) && u.rawUserInfo == null && u.port == -1)
        val license = text("LicenseShortName").ifBlank { text("UsageTerms") }
        return WikiImageInfo(url, page, text("Artist").ifBlank { text("Credit") }, license)
    }
}

data class WikiImageInfo(val url: String, val page: String, val author: String, val license: String)
data class WikiImageAsset(val info: WikiImageInfo, val bytes: ByteArray)
data class WikiImageSaveResult(val saved: Int, val total: Int)

/** One bounded queue per repository; disk operations and decoding run off the UI thread. */
internal class WikiImageStore(private val cache: File, private val pins: File,
    private val metadata: suspend (String, WikiImage) -> WikiImageInfo,
    private val fetch: suspend (String) -> ByteArray = WikiImages::download) {
    private val mutex = Mutex()
    private fun read(file: File): WikiImageAsset? = runCatching {
        require(file.length() <= 4_300_000)
        val j = JSONObject(file.readText())
        val info = WikiImageInfo(j.getString("url"), j.getString("page"), j.getString("author"), j.getString("license"))
        require(WikiImages.trusted(info.url))
        val page = URI(info.page)
        require(page.scheme == "https" && (page.host == "commons.wikimedia.org" || page.host in WikipediaRepository.languages.keys.map { "$it.wikipedia.org" }) && page.rawUserInfo == null && page.port == -1)
        val bytes = Base64.decode(j.getString("bytes"), Base64.NO_WRAP)
        require(WikiImages.key(Base64.encodeToString(bytes, Base64.NO_WRAP)) == j.getString("checksum"))
        WikiImages.checkBitmap(bytes)
        WikiImageAsset(info, bytes)
    }.getOrNull()
    private fun encode(asset: WikiImageAsset): String {
        val bytes = Base64.encodeToString(asset.bytes, Base64.NO_WRAP)
        return JSONObject().put("url", asset.info.url).put("page", asset.info.page).put("author", asset.info.author).put("license", asset.info.license)
            .put("bytes", bytes).put("checksum", WikiImages.key(bytes)).toString()
    }
    private fun write(directory: File, name: String, text: String) {
        check(directory.isDirectory || directory.mkdirs())
        val temp = File.createTempFile("wiki-image-", ".tmp", directory)
        try { temp.writeText(text); check(temp.renameTo(File(directory, name))) } finally { temp.delete() }
    }
    suspend fun load(language: String, image: WikiImage): WikiImageAsset = withContext(Dispatchers.IO) { mutex.withLock {
        require(WikiImages.fileTitle(image.url) == image.title)
        val name = WikiImages.key(image.url) + ".json"
        read(File(pins, name))?.let { return@withLock it }
        read(File(cache, name))?.let { return@withLock it }
        val info = metadata(language, image)
        val asset = WikiImageAsset(info, fetch(info.url).also(WikiImages::checkBitmap))
        coroutineContext.ensureActive()
        val text = encode(asset)
        // Cache eviction is safe: pinned pictures live in a separate directory.
        var bytes = text.toByteArray().size.toLong()
        cache.listFiles().orEmpty().filter { it.extension == "json" && it.name != name }.sortedByDescending { it.lastModified() }.forEach {
            if (bytes + it.length() > 20L * 1024 * 1024) it.delete() else bytes += it.length()
        }
        write(cache, name, text)
        asset
    } }
    suspend fun pin(language: String, image: WikiImage) {
        val asset = load(language, image)
        withContext(Dispatchers.IO) { mutex.withLock {
            val name = WikiImages.key(image.url) + ".json"
            val text = encode(asset)
            check(pins.listFiles().orEmpty().filter { it.extension == "json" && it.name != name }.sumOf { it.length() } + text.toByteArray().size <= 80L * 1024 * 1024)
            coroutineContext.ensureActive(); write(pins, name, text)
        } }
    }
    suspend fun prune(urls: Set<String>) = withContext(Dispatchers.IO) { mutex.withLock {
        val keep = urls.map { WikiImages.key(it) + ".json" }.toSet()
        pins.listFiles().orEmpty().filter { it.extension == "json" && it.name !in keep }.forEach { check(it.delete()) }
    } }
}
