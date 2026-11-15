package com.hanreader.app.en

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.zh.TokenKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional, local public-domain corpus; exports counts, never book passages. */
class EnEngineBookAuditTest {
    @Test fun prepareCompletePublicDomainBooks() {
        val root = System.getenv("HANREADER_EN_AUDIT_SOURCE")
        assumeTrue("Set HANREADER_EN_AUDIT_SOURCE to the audited Gutenberg source directory", root != null)
        val engine = EnEngineTest.engine
        val reports = JSONArray()
        for (name in listOf("alice", "sherlock")) {
            val source = File(root!!, "$name.txt").readText()
            val start = Regex("(?m)^\\*\\*\\* START OF .+?\\*\\*\\*\\s*$").find(source)!!
            val end = Regex("(?m)^\\*\\*\\* END OF .+?\\*\\*\\*\\s*$").find(source)!!
            val book = TxtBook(source.substring(start.range.last + 1, end.range.first).toByteArray(), name)
            val started = System.nanoTime()
            var tokens = 0
            var phrases = 0
            var hintable = 0
            var paragraphs = 0
            for (chapter in book.chapters) {
                val texts = book.blocks(chapter.index).mapNotNull { (it as? EpubBlock.Text)?.text }
                val prepare = engine.chapter(texts, TextOptions())
                for (text in texts) {
                    val prepared = prepare(text)
                    assertEquals(text, prepared.tokens.joinToString("") { it.text })
                    prepared.tokens.forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
                    for (token in prepared.tokens) if (token.kind == TokenKind.HAN) {
                        tokens++
                        if (' ' in token.pinyin) phrases++
                        if (!engine.glossShort(token).isNullOrBlank()) hintable++
                    }
                    paragraphs++
                }
            }
            assertTrue(tokens > 1000)
            reports.put(JSONObject().put("book", name).put("tokens", tokens).put("paragraphs", paragraphs)
                .put("phrase_heads", phrases).put("tokens_with_visible_hint", hintable)
                .put("prepare_and_lookup_ms", (System.nanoTime() - started) / 1_000_000))
        }
        val out = File("build/reports/en-reader-audit.json")
        out.parentFile?.mkdirs()
        out.writeText(JSONObject().put("books", reports)
            .put("limitations", "Visible hints may be Russian or English; counts do not measure sense accuracy. Desktop JVM timing, not a phone benchmark.").toString(2))
    }
}
