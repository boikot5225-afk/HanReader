package com.hanreader.app.es

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SortedTsvTest {
    @Test
    fun mappedFileWithIndexMatchesInMemory() {
        val dir = createTempDir()
        val data = File(dir, "d.tsv").apply { writeText("abrir\tгл.\nañadir\tгл.\ncasa\tсущ.\tдом\nñandú\tсущ.\n") }
        val idx = File(dir, "d.tsv.idx")
        val mapped = SortedTsv.map(data, idx)
        assertEquals(listOf("сущ.", "дом"), mapped.get("casa"))
        assertNull(mapped.get("cas"))
        assertEquals(4, mapped.size)
        // Second open uses the written index.
        assertEquals(listOf("гл."), SortedTsv.map(data, idx).get("añadir"))
        assertEquals(listOf("сущ."), SortedTsv.of(data.readBytes()).get("ñandú"))
        dir.deleteRecursively()
    }

    @Test
    fun realDictionaryOpensQuickly() {
        val dir = createTempDir()
        val src = File("src/main/assets/es/forms.tsv")
        val copy = File(dir, "forms.tsv").also { src.copyTo(it) }
        SortedTsv.map(copy, File(dir, "forms.tsv.idx"))
        val t = System.nanoTime()
        val f = SortedTsv.map(copy, File(dir, "forms.tsv.idx"))
        val ms = (System.nanoTime() - t) / 1_000_000
        assertEquals("hablar", f.get("habló")!!.first().removePrefix("!").substringBefore(":"))
        println("forms.tsv mapped with index in $ms ms")
        dir.deleteRecursively()
    }
}
