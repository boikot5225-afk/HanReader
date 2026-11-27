package com.hanreader.app.web

import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

/**
 * Downloads one page the reader asked for: no cookies, no scripts, nothing of the reader's sent
 * besides the address. https only; at most [MAX_REDIRECTS] redirects and [MAX_BYTES] bytes.
 */
object WebPages {
    const val MAX_BYTES = 5_000_000
    private const val MAX_REDIRECTS = 5

    /**
     * The article at [url]. [httpsOnly] is off only in tests (a local server).
     * [active]: false stops the download (the coroutine was cancelled).
     */
    fun article(url: String, userAgent: String, httpsOnly: Boolean = true, active: () -> Boolean = { true }): WebArticle {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val target = URL(current)
            if (httpsOnly && target.protocol != "https") throw WebArticleException("Открываются только адреса https://")
            val connection = target.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            connection.setRequestProperty("Accept-Language", "es,en;q=0.9,zh;q=0.8,ru;q=0.7")
            try {
                val code = try { connection.responseCode } catch (e: IOException) {
                    throw WebArticleException("Сайт не отвечает. Проверь интернет и адрес.")
                }
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw WebArticleException("Сайт отправил на неизвестный адрес")
                    val next = URI(current).resolve(location.trim()).toString()
                    current = if (httpsOnly) WebArticles.normalize(next) ?: throw WebArticleException("Сайт отправил на неподдерживаемый адрес") else next
                    return@repeat
                }
                when (code) {
                    200, 203 -> Unit
                    401, 403 -> throw WebArticleException("Сайт не открывает эту страницу без входа или проверки «я не робот».")
                    404, 410 -> throw WebArticleException("Страница не найдена (ошибка $code).")
                    429 -> throw WebArticleException("Сайт просит подождать. Попробуй позже.")
                    else -> throw WebArticleException("Сайт ответил ошибкой $code. Попробуй позже.")
                }
                val type = connection.contentType.orEmpty().lowercase()
                val mime = type.substringBefore(';').trim()
                if (mime.isNotEmpty() && mime != "text/html" && mime != "application/xhtml+xml" && mime != "text/plain") {
                    throw WebArticleException(if ("epub" in mime || "pdf" in mime || "fb2" in mime || "zip" in mime || "msword" in mime || "officedocument" in mime)
                        "По ссылке файл, а не страница. Скачай его и открой в библиотеке: «Добавить книгу»."
                        else "По ссылке не веб-страница ($mime).")
                }
                if (connection.contentLengthLong > MAX_BYTES) throw WebArticleException("Страница слишком большая")
                val bytes = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16_384)
                    while (true) {
                        if (!active()) throw WebArticleException("Загрузка отменена")
                        val n = input.read(buffer); if (n < 0) break
                        if (bytes.size() + n > MAX_BYTES) throw WebArticleException("Страница слишком большая")
                        bytes.write(buffer, 0, n)
                    }
                }
                val charset = charsetOf(type)
                val finalUrl = connection.url.toString()
                if (mime == "text/plain") return WebArticles.fromPlainText(String(bytes.toByteArray(), charset ?: Charsets.UTF_8), finalUrl)
                // No charset in the header: Jsoup reads <meta charset> (GBK and Big5 sites still exist).
                val doc = Jsoup.parse(ByteArrayInputStream(bytes.toByteArray()), charset?.name(), finalUrl)
                return WebArticles.extract(doc, finalUrl)
            } finally { connection.disconnect() }
        }
        throw WebArticleException("Слишком много переадресаций")
    }

    internal fun charsetOf(contentType: String): Charset? {
        val name = Regex("charset=\"?([\\w.:-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)?.lowercase() ?: return null
        // GB2312 and GBK pages often use characters only GB18030 (their superset) names.
        val fixed = if (name == "gb2312" || name == "gbk" || name == "x-gbk") "GB18030" else name
        return runCatching { Charset.forName(fixed) }.getOrNull()
    }
}
