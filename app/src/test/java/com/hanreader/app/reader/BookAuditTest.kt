package com.hanreader.app.reader

import com.hanreader.app.epub.BookFiles
import com.hanreader.app.epub.EpubBlock
import com.hanreader.app.zh.TokenKind
import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Test
import java.io.File

/**
 * A whole Chinese book through the reader's dictionary, to find what to fix (not run by default):
 * every word with its count, reading, hint, card meaning and first sentence, most frequent first;
 * hints made of parts («язык + передавать») show what the dictionary lacks. Names the book
 * analysis finds are used as in the app. The book stays out of the repository.
 *
 *   AUDIT_BOOK=/path/book.epub AUDIT_OUT=/tmp/audit.tsv gradle :app:testDebugUnitTest --tests '*BookAuditTest*'
 *
 * Output: AUDIT_OUT (count, word, reading, hint, card, sentence), .seg (segmented paragraphs),
 * .names (names found).
 */
class BookAuditTest {
    @Test
    fun audit() {
        val path = System.getenv("AUDIT_BOOK") ?: return
        fun lines(f: String) = File("src/main/assets/zh/$f").bufferedReader().readLines().asSequence()
        val e = ZhEngine(ZhSegmenterTest.dict, 0, russian = ZhEngine.parseRussian(lines("ru.tsv")),
            russianAi = ZhEngine.parseRussian(lines("ru_ai.tsv")), russianChars = ZhEngine.parseRussianChars(lines("ru_chars.tsv")))
        val book = BookFiles.open(File(path))
        val analysis = BookAnalyzer.analyze(1, book, e.segmenter, ZhSegmenterTest.dict)
        val names = analysis.words.filter { it.ignored }.map { it.word }.toSet()
        File(System.getenv("AUDIT_OUT") + ".names").writeText(names.sorted().joinToString("\n"))
        class Row(val word: String, val reading: String, var n: Int, val gloss: String, val card: String, val sentence: String)
        val rows = LinkedHashMap<String, Row>()
        val segs = StringBuilder()
        for (c in book.chapters) {
            val texts = book.blocks(c.index).mapNotNull { (it as? EpubBlock.Text)?.text }
            val prep = e.chapter(texts, com.hanreader.app.lang.TextOptions(extra = names))
            for (t in texts) {
                val p = prep(t)
                val toks = p.tokens.filter { it.kind == TokenKind.HAN }
                segs.append(toks.joinToString("|") { it.text }).append('\n')
                for (tk in toks) {
                    val key = tk.text + "\t" + tk.pinyin
                    rows.getOrPut(key) {
                        Row(tk.text, tk.pinyin, 0, e.glossShort(tk.text, tk.pinyin.ifEmpty { null }) ?: "",
                            e.cardTranslation(tk.text, tk.pinyin.ifEmpty { null }) ?: "", ChapterBuilder.sentence(p.text, tk.start, tk.end).take(80))
                    }.n++
                }
            }
        }
        File(System.getenv("AUDIT_OUT")).writeText(rows.values.sortedByDescending { it.n }
            .joinToString("\n") { "${it.n}\t${it.word}\t${it.reading}\t${it.gloss}\t${it.card}\t${it.sentence}" })
        File(System.getenv("AUDIT_OUT") + ".seg").writeText(segs.toString())
    }
}
