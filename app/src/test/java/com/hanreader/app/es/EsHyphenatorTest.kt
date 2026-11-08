package com.hanreader.app.es

import org.junit.Assert.assertEquals
import org.junit.Test

class EsHyphenatorTest {
    private fun s(w: String) = EsHyphenator.syllables(w)

    @Test
    fun syllablesByTheUsualRules() {
        assertEquals("me-dia-no-che", s("medianoche"))
        assertEquals("con-ver-ti-das", s("convertidas"))
        assertEquals("es-tre-chos", s("estrechos"))
        assertEquals("cons-truc-ción", s("construcción"))
        assertEquals("ham-bre", s("hambre"))
        assertEquals("ca-rre-te-ra", s("carretera"))
        assertEquals("leer", s("leer"))
        assertEquals("pe-río-do", s("período"))
        assertEquals("que-da-ba", s("quedaba"))
        assertEquals("guerri-lle-ro".replace("guerri", "gue-rri"), s("guerrillero"))
    }

    @Test
    fun noSingleLetterAtAnEdge() {
        assertEquals("atra-sa-do", s("atrasado"))
        assertEquals("ríos", s("ríos"))
        assertEquals(emptyList<Int>(), EsHyphenator.points("O'Donnell"))
        assertEquals(emptyList<Int>(), EsHyphenator.points("sol"))
    }
}
