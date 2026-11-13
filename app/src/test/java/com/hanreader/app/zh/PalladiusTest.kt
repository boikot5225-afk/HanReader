package com.hanreader.app.zh

import org.junit.Assert.assertEquals
import org.junit.Test

class PalladiusTest {
    @Test
    fun syllables() {
        val cases = mapOf("luó" to "ло", "fēi" to "фэй", "hán" to "хань", "hào" to "хао", "yuán" to "юань", "zhì" to "чжи",
            "bāng" to "бан", "zēng" to "цзэн", "rì" to "жи", "huá" to "хуа", "yǐn" to "инь", "jiàn" to "цзянь", "xióng" to "сюн",
            "qū" to "цюй", "xué" to "сюэ", "lǜ" to "люй", "sī" to "сы", "cí" to "цы", "shí" to "ши", "guō" to "го", "wēi" to "вэй",
            "yōu" to "ю", "niú" to "ню", "liè" to "ле", "qiáng" to "цян", "zhuāng" to "чжуан", "ér" to "эр")
        for ((p, r) in cases) assertEquals(p, r, Palladius.syllable(p))
    }

    @Test
    fun names() {
        assertEquals("Ло Фэй", Palladius.name(listOf("luó", "fēi"), chinese = true))
        assertEquals("Юань Чжибан", Palladius.name(listOf("yuán", "zhì", "bāng"), chinese = true))
        assertEquals("Дэсыли", Palladius.name(listOf("dé", "sī", "lǐ"), chinese = false))
    }
}
