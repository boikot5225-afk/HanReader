package com.hanreader.app.web

import com.hanreader.app.epub.BookFiles
import com.hanreader.app.epub.BookFormat
import com.hanreader.app.epub.EpubBlock
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** Pages written for this test: a news page with its usual clutter, a GBK novel page, a script-drawn page. */
class WebArticlesTest {
    private val story = listOf(
        "El tren salió de la estación cuando todavía era de noche, y nadie en el andén levantó la mano para despedirse.",
        "Marta llevaba una maleta pequeña, un cuaderno con las tapas gastadas y una carta que no pensaba abrir hasta llegar.",
        "En el vagón, un hombre mayor leía el periódico en voz baja, como si quisiera convencer a las noticias de algo.",
        "Cuando amaneció, los campos se llenaron de una luz amarilla, y ella por fin abrió la carta, despacio, sin miedo."
    )

    private val newsPage = """
        <html lang="es-MX"><head><title>El tren de la noche | Diario Ejemplo</title>
        <meta property="og:site_name" content="Diario Ejemplo"><meta name="author" content="Ana Ruiz"></head>
        <body class="page has-sidebar">
        <header class="site-header"><nav><a href="/">Inicio</a> <a href="/mundo">Mundo</a> <a href="/deportes">Deportes</a></nav></header>
        <div class="cookie-banner">Usamos cookies para mejorar tu experiencia, acepta para continuar navegando.</div>
        <main><article>
          <h1>El tren de la noche</h1>
          <p>${story[0]}</p>
          <p>${story[1]} <a href="/x">Marta</a> sonrió.</p>
          <h2>La carta</h2>
          <blockquote><p>${story[2]}</p></blockquote>
          <p>${story[3]}</p>
          <p><a href="/leer-mas">Lee también: otras historias de trenes y estaciones</a></p>
          <div class="share-tools"><a href="#">Facebook</a> <a href="#">Twitter</a></div>
        </article></main>
        <aside class="sidebar"><h3>Lo más leído</h3><ul><li><a href="/1">Una noticia muy leída sobre la economía de hoy</a></li></ul></aside>
        <section id="comments" class="comments"><p>Un comentario larguísimo de un lector, con opiniones, comas, y más comas, que no es parte del artículo.</p></section>
        <footer><p>© Diario Ejemplo, todos los derechos reservados, aviso legal, privacidad.</p></footer>
        </body></html>
    """.trimIndent()

    @Test fun sharedLinksAreRecognisedAndLongTextsStayText() {
        assertEquals("https://example.com/a/b?x=1", WebArticles.linkIn("https://example.com/a/b?x=1"))
        // Chrome: page title, then the link with a text fragment.
        assertEquals("https://www.example.com/news/item", WebArticles.linkIn("Una noticia\nhttp://www.example.com:80/news/item#:~:text=tren"))
        assertEquals("https://example.com/a", WebArticles.linkIn("Mira esto: https://example.com/a."))
        assertEquals("https://example.com/%D1%81%D1%82%D0%B0%D1%82%D1%8C%D1%8F", WebArticles.linkIn("https://example.com/статья"))
        assertEquals("https://xn--80aswg.xn--p1ai/", WebArticles.linkIn("https://сайт.рф"))
        assertNull(WebArticles.linkIn(story.joinToString(" ") + " https://example.com/a"))
        assertNull(WebArticles.linkIn("https://example.com/a https://example.com/b"))
        assertNull(WebArticles.linkIn("ftp://example.com/a"))
        assertNull(WebArticles.linkIn("https://localhost/a"))
        assertNull(WebArticles.linkIn("el tren de la noche"))
    }

    @Test fun articleIsFoundWithoutMenusCommentsAndLinkLists() {
        val a = WebArticles.extract(Jsoup.parse(newsPage, "https://diario.example/tren"), "https://diario.example/tren")
        assertEquals("El tren de la noche", a.title)
        assertEquals("Diario Ejemplo", a.site)
        assertEquals("Ana Ruiz", a.author)
        assertEquals("es", a.language)
        assertEquals(listOf(
            WebBlock(story[0]), WebBlock(story[1] + " Marta sonrió."), WebBlock("La carta", heading = 2),
            WebBlock(story[2], quote = true), WebBlock(story[3])
        ), a.blocks)
    }

    @Test fun gbkNovelPageWithLineBreaks() {
        val lines = listOf("那天晚上一直在下雨，街上没有一个人。", "他站在门口，等一个不会来的人。", "门开了，风把灯吹得摇摇晃晃。",
            "她说：“你还在等吗？”他没有回答，只是点了点头。")
        val html = """<html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"><title>第一章 雨夜_小说网</title></head>
            <body><div id="menu"><a href="/">首页</a><a href="/top">排行榜</a></div>
            <div id="content">${lines.joinToString("<br><br>") { "&nbsp;&nbsp;&nbsp;&nbsp;$it" }}</div>
            <div class="footer">本站所有小说均来自网络，版权归原作者所有。</div></body></html>"""
        val doc = Jsoup.parse(ByteArrayInputStream(html.toByteArray(charset("GBK"))), null, "https://novel.example/1.html")
        val a = WebArticles.extract(doc, "https://novel.example/1.html")
        assertEquals("第一章 雨夜", a.title)
        assertEquals(lines, a.blocks.map { it.text })
        assertEquals(java.nio.charset.Charset.forName("GB18030"), WebPages.charsetOf("text/html; charset=GB2312"))
    }

    @Test fun scriptDrawnPageUsesStructuredDataOrRefuses() {
        val shell = """<html><head><title>App</title><script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"@type":"NewsArticle","headline":"El tren",
            "articleBody":"${story[0]}\n\n${story[1]}"}]}</script></head><body><div id="root"></div></body></html>"""
        val a = WebArticles.extract(Jsoup.parse(shell), "https://app.example/n")
        assertEquals(listOf(story[0], story[1]), a.blocks.map { it.text })
        try {
            WebArticles.extract(Jsoup.parse("<html><body><div id='root'>Cargando…</div></body></html>"), "https://app.example/n")
            fail()
        } catch (e: WebArticleException) { assertEquals(WebArticles.NO_TEXT, e.message) }
    }

    @Test fun articleOpensAsAnEpubBook() {
        val article = WebArticle("https://diario.example/tren?a=1&b=2", "Trenes & <estaciones>", "Diario", "", "es",
            listOf(WebBlock(story[0]), WebBlock("Parte 2", heading = 3), WebBlock("“Cita” <con> & signos", quote = true), WebBlock(story[1])))
        val file = File.createTempFile("web", ".epub").apply { writeBytes(WebArticles.epub(article, "2026-11-27")); deleteOnExit() }
        assertEquals(BookFormat.EPUB, BookFiles.sniff(file))
        BookFiles.open(file, BookFormat.EPUB).use { book ->
            assertEquals("Trenes & <estaciones>", book.title)
            assertEquals("Diario", book.author)
            assertEquals(1, book.chapters.size)
            val blocks = book.blocks(0).map { it as EpubBlock.Text }
            assertEquals(listOf("Trenes & <estaciones>", story[0], "Parte 2", "“Cita” <con> & signos", story[1]), blocks.map { it.text })
            assertEquals(listOf(true, false, true, false, false), blocks.map { it.heading })
        }
    }

    @Test fun downloadFollowsRedirectsReadsCharsetAndExplainsFailures() {
        // A few lines of HTTP over a socket: com.sun.net.httpserver is not on the Android test classpath.
        val routes = HashMap<String, ByteArray>()
        fun route(path: String, code: Int, type: String?, body: ByteArray, location: String? = null) {
            val head = buildString {
                append("HTTP/1.1 $code X\r\n")
                type?.let { append("Content-Type: $it\r\n") }
                location?.let { append("Location: $it\r\n") }
                append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            }
            routes[path] = head.toByteArray(Charsets.ISO_8859_1) + body
        }
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val path = reader.readLine().orEmpty().split(' ').getOrElse(1) { "/" }
                    while (!reader.readLine().isNullOrEmpty()) { }
                    s.getOutputStream().apply { write(routes[path] ?: "HTTP/1.1 404 X\r\nContent-Length: 0\r\n\r\n".toByteArray()); flush() }
                }
            }
        }.apply { isDaemon = true }.start()
        val latin = newsPage.toByteArray(Charsets.ISO_8859_1)
        route("/old", 301, null, ByteArray(0), "/new")
        route("/new", 200, "text/html; charset=ISO-8859-1", latin)
        route("/gone", 404, "text/html", "no".toByteArray())
        route("/file", 200, "application/pdf", "%PDF".toByteArray())
        route("/loop", 302, null, ByteArray(0), "/loop")
        route("/plain", 200, "text/plain; charset=utf-8", story.joinToString("\n\n").toByteArray())
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val a = WebPages.article("$base/old", "test", httpsOnly = false)
            assertEquals("$base/new", a.url)
            assertEquals(story[0], a.blocks.first().text)
            assertEquals(story, WebPages.article("$base/plain", "test", httpsOnly = false).blocks.map { it.text })
            fun message(path: String) = try { WebPages.article("$base$path", "test", httpsOnly = false); "" } catch (e: WebArticleException) { e.message!! }
            assertTrue(message("/gone").contains("не найдена"))
            assertTrue(message("/file").contains("«Добавить книгу»"))
            assertEquals("Слишком много переадресаций", message("/loop"))
            try { WebPages.article("$base/new", "test"); fail() } catch (e: WebArticleException) { assertTrue(e.message!!.contains("https")) }
            assertFalse(runCatching { WebPages.article("$base/new", "test", httpsOnly = false, active = { false }) }.isSuccess)
        } finally { server.close() }
    }
}
