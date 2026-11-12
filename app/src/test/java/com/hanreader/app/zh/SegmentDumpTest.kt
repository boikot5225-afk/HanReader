package com.hanreader.app.zh

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Developer-only: segments every line of a text file, for measuring the segmenter against a
 * reference with tools/seg_eval.py. HANREADER_SEG_IN=sentences.txt HANREADER_SEG_OUT=out.txt
 * Output: one line per input line, tokens joined by a space.
 */
class SegmentDumpTest {
    @Test
    fun dump() {
        val input = System.getenv("HANREADER_SEG_IN")
        val output = System.getenv("HANREADER_SEG_OUT")
        assumeTrue(input != null && output != null && File(input).exists())
        val seg = ZhSegmenterTest.segmenter
        File(output!!).printWriter().use { w ->
            for (line in File(input!!).readLines()) w.println(seg.segment(line).joinToString(" ") { it.text })
        }
    }
}
