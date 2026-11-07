package com.hanreader.app.reader

import com.hanreader.app.zh.ZhSegmenterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A single character shows the meanings of the reading on the page. */
class RussianReadingsTest {
    private val chars = ZhEngine.parseRussianChars(sequenceOf(
        "得\tdé\tполучить; приобрести",
        "得\tde\tструктурная частица после глагола: степень, результат",
        "得\tděi\tнужно; должен",
        "的\tde\tпритяжательная частица; частица определения",
        "的\tdì\tцель; мишень"
    ))
    private val wiki = ZhEngine.parseRussian(sequenceOf(
        "的\tяркий; чистый ; белый лоб лошадей",
        "着\t着 (ключ 109 目+7 черт, всего 12 черт, метод ввода cangjie: 廿手月山) ; надевать",
        "特点\tособенность ; черта характера"
    ))
    private val engine = ZhEngine(ZhSegmenterTest.dict, 0, russian = wiki, russianChars = chars)

    @Test
    fun readingDecides() {
        assertTrue(engine.russianSenses("得", "de").first().startsWith("структурная частица"))
        assertEquals("получить", engine.russianSenses("得", "dé").first())
        assertEquals("нужно", engine.russianSenses("得", "děi").first())
        // No reading given: the dictionary's reading; unknown reading: the same.
        assertTrue(engine.russianSenses("的").first().startsWith("притяжательная"))
        assertTrue(engine.russianSenses("的", "xyz").first().startsWith("притяжательная"))
        // Per-reading meanings win over the Wiktionary's character page.
        assertEquals(ZhEngine.RussianSource.READING, engine.russianSource("的"))
        assertEquals(listOf("dé", "děi"), engine.otherReadings("得", "de").map { it.first })
        assertTrue(engine.cardTranslation("得", "de")!!.startsWith("структурная частица"))
    }

    @Test
    fun referenceLinesAreNotMeanings() {
        assertEquals(listOf("надевать"), engine.russianSenses("着"))
        assertEquals(listOf("особенность", "черта характера"), engine.russianSenses("特点"))
    }

    /** The shipped table covers the particles that led to the bug. */
    @Test
    fun shippedTable() {
        val f = File("src/main/assets/zh/ru_chars.tsv")
        org.junit.Assume.assumeTrue(f.exists())
        val table = f.bufferedReader().useLines { ZhEngine.parseRussianChars(it) }
        assertTrue(table.size > 3_000)
        val e = ZhEngine(ZhSegmenterTest.dict, 0, russianChars = table)
        for ((ch, reading) in listOf("的" to "de", "地" to "de", "得" to "de", "着" to "zhe", "了" to "le")) {
            val s = e.russianSenses(ch, reading).joinToString("; ")
            assertTrue("$ch $reading: $s", "частиц" in s || "суффикс" in s || "показатель" in s || "указывает" in s)
        }
    }
}
