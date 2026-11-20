package com.hanreader.app.wiki

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WikiDocumentsTest {
    private val article = WikiArticle("es", 42, "Ciencia", "Una introducción.", 321, 1234, false)
    private fun response(html: String, revision: Long = 321) = JSONObject().put("amautaFetchedAt", 1234).put("parse",
        JSONObject().put("pageid", 42).put("revid", revision).put("text", html))

    @Test fun pinnedHtmlBecomesInertParagraphsHeadingsListsTablesAndFormulas() {
        val html = """<div class='mw-parser-output'><script>alert('secret')</script><style>.x{}</style>
            <div class='navbox'><p>Navigation</p></div><p>Una <a href='https://evil.example'>introducción</a><sup class='reference'>[1]</sup>.</p>
            <h2>Historia <span class='mw-editsection'>editar</span></h2><p>Texto completo que no está en la introducción.</p>
            <ul><li><p>Primero</p><ul><li>Segundo</li></ul></li></ul><blockquote><p>Una cita</p></blockquote>
            <table><tr><th>Año</th><th>Valor</th></tr><tr><td><p>2020</p></td><td>42</td></tr></table>
            <p>Una fórmula <math alttext='E=mc²'><mi>E</mi></math><img src='https://evil.example/tracker.png'/></p></div>"""
        val document = WikiDocuments.parse(article, response(html))
        val text = document.blocks.joinToString("\n") { it.text }
        assertTrue(text.contains("Texto completo")); assertTrue(text.contains("E=mc²"))
        listOf("secret", "Navigation", "tracker", "evil.example", "editar", "[1]").forEach { assertFalse(text.contains(it)) }
        assertEquals(1, document.blocks.count { it.text == "Primero" }); assertEquals(1, document.blocks.count { it.text == "Segundo" })
        assertEquals(1, document.blocks.count { it.text == "Una cita" })
        assertTrue(document.blocks.any { it.heading == 2 && it.text == "Historia" })
        assertTrue(document.blocks.any { it.text == "2020 | 42" })
        assertEquals(document, WikiDocuments.decode(WikiDocuments.encode(document)))
    }
    @Test fun differentRevisionsEmptyTruncatedOrOversizedContentCannotBecomeValidSnapshots() {
        assertTrue(runCatching { WikiDocuments.parse(article, response("<p>text</p>", 999)) }.isFailure)
        assertTrue(runCatching { WikiDocuments.parse(article, response("<script>only script</script>")) }.isFailure)
        assertTrue(runCatching { WikiDocuments.decode("{truncated") }.isFailure)
        assertTrue(runCatching { WikiDocuments.parse(article.copy(language = "../evil"), response("<p>text</p>")) }.isFailure)
        assertTrue(runCatching { WikiDocuments.parse(article, response("<p>${"a".repeat(400_001)}</p>")) }.isFailure)
    }
    @Test fun longParagraphsAreBoundedWithoutDroppingWordsOrBreakingSurrogates() {
        val text = (1..2500).joinToString(" ") { "palabra$it" } + " 𝒳final"
        val document = WikiDocuments.parse(article, response("<p>$text</p>"))
        assertTrue(document.blocks.size > 1)
        assertTrue(document.blocks.all { it.text.length <= 3000 })
        assertEquals(text, document.blocks.joinToString(" ") { it.text })
        assertNotEquals(document.key, document.copy(article = article.copy(revision = 322)).key)
    }
    @Test fun imagesKeepTheirParagraphAnchorsAndRejectTrackersIconsAndMath() {
        val url = "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Example.svg/330px-Example.svg.png"
        val html = """<p>Primero.</p><figure><img src="${url.removePrefix("https:")}?utm_source=en.wikipedia.org&amp;utm_campaign=parser" width="330" height="200" alt="Ejemplo"/><figcaption>Una ilustración.</figcaption></figure>
            <p>Segundo.</p><img src="https://evil.example/pixel.png" width="400" height="300"/>
            <img src="$url" width="16" height="16"/><span class="mwe-math-element"><img src="$url" width="100" height="80"/></span>"""
        val doc = WikiDocuments.parse(article, response(html))
        assertEquals(listOf("Primero.", "Segundo."), doc.blocks.map { it.text })
        assertEquals(listOf(WikiImage(url, "File:Example.svg", "Una ilustración.", 330, 200, 1)), doc.images)
        assertEquals(doc, WikiDocuments.decode(WikiDocuments.encode(doc)))
        assertEquals(doc.key, doc.copy(images = emptyList()).key)
        val legacy = JSONObject(WikiDocuments.encode(doc.copy(images = emptyList()))).put("version", 1).apply { remove("images") }
        assertEquals(doc.copy(images = emptyList()), WikiDocuments.decode(legacy.toString()))
    }
    @Test fun onlyOfficialWikimediaImagePathsCanBeUsed() {
        listOf("http://upload.wikimedia.org/wikipedia/a.png", "https://upload.wikimedia.org.evil.org/wikipedia/a.png",
            "https://user@upload.wikimedia.org/wikipedia/a.png", "https://upload.wikimedia.org:443/wikipedia/a.png",
            "https://upload.wikimedia.org/wikipedia/../a.png", "https://upload.wikimedia.org/wikipedia/%2e%2e/a.png",
            "https://upload.wikimedia.org/wikipedia/a.png?tracker=x", "file:///etc/passwd").forEach { assertFalse(it, WikiImages.trusted(it)) }
        assertNull(WikiImages.normalize("https://evil.example/wikipedia/photo.png?utm_source=x"))
        assertNull(WikiImages.normalize("https://thumb.wikimedia.org/wikipedia/photo.png?redirect=https://evil.example"))
        assertEquals("https://thumb.wikimedia.org/wikipedia/photo.png", WikiImages.normalize("//thumb.wikimedia.org/wikipedia/photo.png?utm_source=en.wikipedia.org&utm_content=thumbnail"))
        assertEquals("File:A+B.jpg", WikiImages.fileTitle("https://upload.wikimedia.org/wikipedia/commons/a/ab/A+B.jpg"))
    }

}
