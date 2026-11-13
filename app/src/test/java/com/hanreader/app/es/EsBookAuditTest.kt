package com.hanreader.app.es

import com.hanreader.app.epub.BookFiles
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.lang.TextOptions
import com.hanreader.app.zh.TokenKind
import org.junit.Test
import java.io.File

/**
 * A whole Spanish book through the reader's dictionary, to find what to fix (not run by default):
 * every word with its count, the reading the context chose, the hint over it, the dictionary word
 * and its senses; «?» marks a word the dictionary lacks, «EN» one with English senses only.
 *
 *   AUDIT_BOOK=/path/book.epub AUDIT_OUT=/tmp/audit.tsv gradle :app:testDebugUnitTest --tests '*EsBookAuditTest*'
 *
 * The book stays out of the repository.
 */
class EsBookAuditTest {
    @Test
    fun audit() {
        val path = System.getenv("AUDIT_BOOK") ?: return
        val engine = EsEngineTest.load()
        val book = BookFiles.open(File(path))
        class Row(val word: String, val key: String, var n: Int, val hint: String, val lemma: String, val senses: String, val flag: String, val sentence: String)
        val rows = LinkedHashMap<String, Row>()
        for (c in book.chapters) {
            val texts = book.blocks(c.index).mapNotNull { (it as? EpubBlock.Text)?.text }
            val prep = engine.chapter(texts, TextOptions())
            for (t in texts) {
                val p = prep(t)
                for ((i, tk) in p.tokens.withIndex()) {
                    if (tk.kind != TokenKind.HAN) continue
                    val k = tk.text.lowercase() + "\t" + tk.pinyin
                    rows.getOrPut(k) {
                        val a = engine.cardAnalyses(p.tokens, p.text, i, i).firstOrNull()
                        val flag = when {
                            a == null -> "?"
                            a.entry.ru.isEmpty() -> "EN"
                            else -> ""
                        }
                        Row(tk.text, tk.pinyin, 0, engine.glossShort(tk.text, tk.pinyin.ifEmpty { null }).orEmpty(),
                            a?.entry?.lemma.orEmpty(), a?.let { engine.senses(it.entry).take(3).joinToString(" ; ") }.orEmpty(), flag,
                            com.hanreader.app.reader.ChapterBuilder.sentence(p.text, tk.start, tk.end).take(100))
                    }.n++
                }
            }
        }
        File(System.getenv("AUDIT_OUT")).writeText(rows.values.sortedByDescending { it.n }
            .joinToString("\n") { "${it.n}\t${it.word}\t${it.key}\t${it.hint}\t${it.lemma}\t${it.senses}\t${it.flag}\t${it.sentence}" })
    }
}
