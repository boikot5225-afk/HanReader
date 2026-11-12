package com.hanreader.app.es

import com.hanreader.app.epub.TxtBook
import com.hanreader.app.reader.ChapterBuilder
import com.hanreader.app.reader.KnownMatcher
import com.hanreader.app.reader.ReaderBlock
import com.hanreader.app.zh.TokenKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Spanish module on the real dictionary (assets/es). */
class EsEngineTest {
    private val engine = load()

    @Test
    fun formsLeadToTheirDictionaryWord() {
        val hablo = engine.analyses("habló").first()
        assertEquals("hablar", hablo.entry.lemma)
        assertTrue(hablo.grammar, hablo.grammar.contains("претерит"))
        assertEquals(setOf("ir", "ser"), engine.analyses("fue").map { it.entry.lemma }.toSet())
        assertEquals("casa", engine.lemma("Casas"))
        assertEquals("hablar", engine.lemma("hablo"))
    }

    @Test
    fun attachedPronounsAreTakenOff() {
        assertEquals("dar", engine.lemma("Dámelo"))
        assertEquals("decir", engine.lemma("dígamelo"))
        assertEquals("levantar", engine.lemma("levántate"))
    }

    @Test
    fun russianMeaningsFirst() {
        assertEquals("привет", engine.glossShort("Hola"))
        assertEquals("дом", engine.glossShort("casa"))
        assertNotNull(engine.cardTranslation("hablaban"))
        assertTrue(engine.cardTranslation("hablaban")!!.contains("говорить"))
    }

    @Test
    fun wordsSplitByLettersAndPunctuation() {
        val t = engine.tokenize("¿Qué tal, señor O'Donnell? Bien.")
        assertEquals(listOf("Qué", "tal", "señor", "O'Donnell", "Bien"), t.filter { it.kind == TokenKind.HAN }.map { it.text })
        assertEquals("¿", t.first().text)
        assertTrue(t.first { it.text == "Qué" }.defined)
    }

    @Test
    fun aKnownWordMakesItsFormsKnown() {
        val m = KnownMatcher(setOf("hablar", "y"), engine)
        assertTrue(m.isKnown("habló"))
        assertTrue(m.isKnown("y"))
        assertFalse(m.isKnown("comió"))
    }

    @Test
    fun chapterAndSearchForm() {
        val chapter = ChapterBuilder.build(TxtBook("Ella habló con su madre. Después comió.".toByteArray(), "x"), 0, engine)
        val block = chapter.blocks.filterIsInstance<ReaderBlock.Text>().first()
        assertEquals("habló", block.tokens.first { it.text.startsWith("habl") }.text)
        assertTrue(block.tokens.all { it.pinyin.isEmpty() })
        assertEquals("cancion", engine.searchForm("Canción"))
        assertEquals("Canción".length, engine.searchForm("Canción").length)
    }

    @Test
    fun namesAndBookCountsByDictionaryForm() {
        assertTrue(engine.isName("Blancornelas"))
        assertTrue(engine.isName("Sinaloa"))
        assertFalse(engine.isName("casa"))
        val book = TxtBook("Ella habló. Ellos hablaban. Hablar es fácil, dijo Blancornelas.".toByteArray(), "x")
        val words = engine.analyze(1L, book).words.associate { it.word to it.count }
        assertEquals(3, words["hablar"])
        assertEquals(1, words["Blancornelas"])
    }

    @Test
    fun frequentVerbFormsReadAsTheVerb() {
        assertEquals("ser", engine.lemma("era"))
        assertEquals("ser", engine.lemma("son"))
        assertEquals("haber", engine.lemma("ha"))
        assertEquals("saber", engine.lemma("sé"))
        // The word itself where it is the usual reading: como «как», not a form of comer.
        assertEquals("como", engine.lemma("como"))
        assertEquals("гл.", engine.entry("poder")!!.pos)
        assertTrue(engine.entry("ser")!!.rank in 1..50)
    }

    @Test
    fun setPhrasesAroundAWord() {
        val text = "Sin embargo, llegó a pesar de la lluvia."
        val t = engine.tokenize(text)
        fun at(w: String) = t.indexOfFirst { it.text == w }
        fun phrase(w: String) = engine.phraseAt(t, text, at(w))?.let { text.substring(t[it.first].start, t[it.last].end) }
        assertEquals("Sin embargo", phrase("embargo"))
        assertEquals("a pesar de", phrase("pesar"))
        assertEquals(null, phrase("lluvia"))
        assertTrue(engine.cardTranslation("sin embargo")!!.contains("однако"))
    }

    @Test
    fun levelsByFrequency() {
        val lv = engine.levels
        assertEquals(1, lv.of("ser"))
        assertEquals(1, lv.of("habló"))
        assertTrue(lv.of("orinar") >= 4)
        assertEquals(7, lv.estimate("comemierda"))
        assertTrue("casa" in lv.knownAt(1))
        assertTrue(lv.knownAt(2).size in 1_200..1_600)
        assertEquals("B1", lv.label(3))
    }

    @Test
    fun usualReadingsAndContext() {
        assertEquals("ataque", engine.lemma("ataques"))
        assertEquals("casa", engine.lemma("casas"))
        // «se alza»: the verb alzar, not the noun alza.
        val t = engine.inContext(engine.tokenize("La prisión se alza en el desierto."))
        assertEquals("alzarse", t.first { it.text == "alza" }.pinyin)
        assertEquals("", t.first { it.text == "prisión" }.pinyin)
        assertFalse(engine.glossShort("desierto")!!.startsWith("геогр"))
    }

    @Test
    fun capitalisedWordsInsideASentenceAreNames() {
        val t = engine.tokenize("Llamada el Alcatraz de las Rocosas, o simplemente Supermax. Los presos")
        val names = engine.sureNames(t).map { t[it.first].text }
        assertEquals(listOf("Alcatraz", "Rocosas", "Supermax"), names)
    }

    /** The cases of an outside review of the Spanish module. */
    @Test
    fun reviewCases() {
        fun ctx(text: String, word: String) = engine.inContext(engine.tokenize(text)).first { it.text == word }.pinyin
        assertEquals("comer", ctx("No como carne.", "como"))
        assertEquals("comer", ctx("Yo como arroz.", "como"))
        assertEquals("", ctx("Como tú, no lo sé.", "Como"))
        assertEquals("ir", ctx("Fue a Madrid.", "Fue"))
        assertEquals("ser", engine.lemma("fue"))
        assertEquals("haber", engine.lemma("haya"))
        assertEquals("hablar", engine.lemma("hablándome"))
        assertEquals("decir", engine.lemma("diciéndoselo"))
        assertEquals("ser", engine.lemma("sos"))
        assertEquals("andar", engine.lemma("andás"))
        assertEquals("tener", engine.lemma("tenés"))
        // An infinitive has no person: «инфинитив + me».
        assertEquals("инфинитив + me", engine.analyses("levantarme").first().grammar)
    }

    @Test
    fun inflectedPhrases() {
        val text = "Me di cuenta de que lo echo de menos."
        val t = engine.tokenize(text)
        fun key(w: String) = engine.phraseAt(t, text, t.indexOfFirst { it.text == w })?.let { engine.phraseKey(t, text, it.first, it.last) }
        assertEquals("darse cuenta", key("cuenta"))
        assertEquals("echar de menos", key("menos"))
    }

    /** The reader's screenshot: «trajeron cola» is the idiom, «había adoptado» the verb adoptar. */
    @Test
    fun idiomsAndPerfectTensesInContext() {
        val text = "Las elecciones trajeron cola, y el cártel había adoptado otra forma."
        val t = engine.inContext(engine.tokenize(text), text)
        fun at(w: String) = t.first { it.text == w }
        assertEquals("traer cola", at("trajeron").pinyin)
        assertEquals(EsEngine.IN_PHRASE, at("cola").pinyin)
        assertEquals("иметь последствия", engine.glossShort("trajeron", "traer cola"))
        assertEquals(null, engine.glossShort("cola", EsEngine.IN_PHRASE))
        assertEquals("adoptar", at("adoptado").pinyin)
    }

    /** A word that is several parts of speech: «haberes» is the noun, «el poder» power, «mañana» tomorrow. */
    @Test
    fun partsOfSpeechOfOneWord() {
        val haberes = engine.analyses("haberes").first()
        assertEquals("сущ.", haberes.entry.pos)
        assertEquals("haber", haberes.entry.lemma)
        assertTrue(haberes.entry.ru.any { it.contains("имущество") })
        assertEquals("гл.", engine.analyses("haber").first().entry.pos)
        assertEquals("haber", engine.lemma("haberes"))
        fun gloss(text: String, word: String): String? {
            val t = engine.inContext(engine.tokenize(text), text).first { it.text == word }
            return engine.glossShort(t.text, t.pinyin)
        }
        assertEquals("власть", gloss("El cártel tomó el poder.", "poder"))
        assertEquals("мочь", gloss("Quiero poder dormir.", "poder"))
        assertEquals("завтра", gloss("Vuelvo mañana.", "mañana"))
        assertEquals("утро", gloss("Llegó por la mañana.", "mañana"))
        assertEquals("завтра", gloss("Trabajo desde mañana.", "mañana"))
        assertEquals("поздно", gloss("Volvieron tarde.", "tarde"))
        assertEquals("вечер", gloss("Vino esa misma tarde.", "tarde"))
        assertEquals("делать", gloss("Al hacer eso se equivocó.", "hacer"))
        assertEquals("врач", engine.glossShort("médico"))
        assertEquals("этот", engine.glossShort("este"))
        // A verb that is not the word's main part of speech still conjugates and takes pronouns.
        assertEquals("гл.", engine.analyses("place").first().entry.pos)
    }

    /** The reader's pages: hints that depend on the neighbours. */
    @Test
    fun hintsByNeighbours() {
        fun gloss(text: String, word: String): String? {
            val t = engine.inContext(engine.tokenize(text), text).first { it.text == word }
            return engine.glossShort(t.text, t.pinyin)
        }
        // A form that is both a noun and an adjective keeps the word's main part of speech.
        assertEquals("прожектор", gloss("Encendió dos potentes reflectores.", "reflectores"))
        assertEquals("внутренний", gloss("Hizo su propia investigación interna.", "interna"))
        // A verb with its preposition, also after an adverb.
        assertEquals("располагать", gloss("Contamos actualmente con hechos.", "Contamos"))
        assertEquals("располагать", gloss("Contamos con hechos.", "Contamos"))
        assertEquals("считать", gloss("Contamos los días.", "Contamos"))
        // Pronominal verbs.
        assertEquals("ограничиваться", gloss("En vez de limitarse a buscar agujeros.", "limitarse"))
        assertEquals("отказываться", gloss("El general se negó.", "negó"))
        assertEquals("уходить", gloss("Me voy mañana.", "voy"))
        // «volver a» is «снова» only before an infinitive.
        assertEquals("снова", gloss("Volvió a llamar.", "Volvió"))
        assertFalse(gloss("Volvió a casa.", "Volvió") == "снова")
        // An adjective that is also a noun, with no noun after it.
        assertEquals("юноша", gloss("Un joven llegó tarde.", "joven"))
        assertEquals("молодой", gloss("El joven médico llegó.", "joven"))
        // Checked over a whole book.
        assertEquals("становиться", gloss("La situación se volvió peligrosa.", "volvió"))
        assertEquals("находиться", gloss("Se estaba investigando.", "estaba"))
        assertFalse(gloss("Firmaron el tratado de paz.", "tratado") == "пытаться")
        assertFalse(gloss("El tratado de comercio.", "tratado") == "пытаться")
        assertEquals("второй", gloss("Llegó el segundo.", "segundo"))
        assertFalse(gloss("Había una casa.", "una") == "объединять")
        assertEquals("пройти через", gloss("Han pasado por mucho.", "pasado"))
    }

    /** The second check over a whole book. */
    @Test
    fun secondBookCheck() {
        fun t(text: String, word: String) = engine.inContext(engine.tokenize(text), text).first { it.text == word }
        fun gloss(text: String, word: String) = t(text, word).let { engine.glossShort(it.text, it.pinyin) }
        // A phrase whose «de» / «a» is joined with the article.
        assertEquals("a pesar de", t("A pesar del apaño de las elecciones.", "A").pinyin)
        assertEquals("несмотря на", gloss("A pesar del apaño de las elecciones.", "A"))
        assertEquals("después de", t("Llegó después del mediodía.", "después").pinyin)
        assertEquals("внутри", gloss("Vive dentro del país.", "dentro"))
        assertFalse(t("No sabía nada del dinero.", "nada").pinyin.contains(' '))
        assertEquals("перейти к", gloss("El nuevo PRI se pasó al libre comercio.", "pasó"))
        assertEquals("исчисляться", gloss("Los asesinados se contaban por centenares.", "contaban"))
        // A form whose own entry is only regional: the ordinary word first.
        assertEquals("начальник", engine.glossShort("jefes"))
        // A name with no Russian meaning gets no hint.
        assertEquals(null, engine.glossShort("Gustavo"))
        assertEquals("резня", engine.glossShort("matanza"))
    }

    /** Combinations of a whole book that read wrongly word by word. */
    @Test
    fun bookCombinations() {
        fun key(text: String, word: String) = engine.inContext(engine.tokenize(text), text).first { it.text == word }.pinyin
        fun gloss(text: String, word: String) = engine.inContext(engine.tokenize(text), text).first { it.text == word }.let { engine.glossShort(it.text, it.pinyin) }
        assertEquals("конспиративные квартиры", gloss("Tenían varias casas francas en Juárez.", "casas"))
        assertEquals("конспиративная квартира", gloss("Lo llevaron a una casa franca.", "casa"))
        assertEquals("триллион долларов", gloss("Un billón de dólares al año.", "billón"))
        assertEquals("миллиарды", gloss("Ganan miles de millones de dólares.", "miles"))
        assertEquals("застреленный", gloss("Fue muerto a tiros en la calle.", "muerto"))
        assertEquals("застрелить", gloss("Lo mataron a tiros.", "mataron"))
        assertEquals("сборочный завод", gloss("Trabajar en las plantas de montaje de la frontera.", "plantas"))
        assertEquals("жилой квартал", gloss("Crecieron a razón de una manzana de viviendas al día.", "manzana"))
        assertEquals("спецназ", gloss("Las fuerzas especiales llegaron.", "fuerzas"))
        assertEquals("обнародовать", gloss("La noticia se hizo pública ayer.", "hizo"))
        assertEquals("генпрокурор", gloss("El fiscal general habló.", "fiscal"))
        assertEquals("давно", gloss("Hace tiempo que no lo veo.", "Hace"))
        assertEquals("сообщать о", gloss("Los informes daban cuenta de fiestas monstruo.", "daban"))
        assertEquals("частные самолёты", gloss("Llegaban en reactores particulares.", "reactores"))
        assertEquals("адресованный", gloss("Había escrito una carta dirigida a Ramón.", "dirigida"))
        assertEquals("попасть в", gloss("Los jóvenes habían caído en una trifulca.", "caído"))
    }

    @Test
    fun ordinaryWordPairsAreNotPhrases() {
        val text = "La casa de la vida de una de esas personas."
        val t = engine.inContext(engine.tokenize(text), text)
        assertTrue(t.none { ' ' in it.pinyin || it.pinyin == EsEngine.IN_PHRASE })
        assertFalse(engine.usefulPhrase("de la"))
        assertFalse(engine.usefulPhrase("la vida"))
        assertTrue(engine.usefulPhrase("sin embargo"))
        assertTrue(engine.usefulPhrase("por qué"))
    }

    @Test
    fun abbreviationsDoNotEndSentences() {
        val was = com.hanreader.app.lang.Languages.current
        com.hanreader.app.lang.Languages.current = com.hanreader.app.lang.Languages.SPANISH
        try { abbreviations() } finally { com.hanreader.app.lang.Languages.current = was }
    }

    private fun abbreviations() {
        val text = "El Sr. Ortega vivía en EE. UU. desde hacía 1.500 días. Luego volvió."
        val chapter = ChapterBuilder.build(TxtBook(text.toByteArray(), "x"), 0, engine)
        val spoken = com.hanreader.app.reader.Sentences.of(chapter).map { it.text }
        assertEquals(listOf("El Sr. Ortega vivía en EE. UU. desde hacía 1.500 días.", "Luego volvió."), spoken)
        val b = chapter.blocks.filterIsInstance<ReaderBlock.Text>().first()
        val at = b.text.indexOf("Ortega")
        assertEquals("El Sr. Ortega vivía en EE. UU. desde hacía 1.500 días.", ChapterBuilder.sentence(b.text, at, at + 6))
    }

    @Test
    fun bookNamesFromCapitalLetters() {
        val text = "Rocosas es una cordillera. Vive cerca de las Rocosas con Supermax. La casa real. El Real Madrid ganó."
        val a = engine.analyze(1L, TxtBook(text.toByteArray(), "x"))
        val names = a.words.filter { it.ignored }.map { it.word }.toSet()
        assertEquals(setOf("Rocosas", "Supermax", "Madrid"), names)
    }

    @Test
    fun genderAndConjugation() {
        assertEquals("сущ., ж. р.", engine.entry("casa")!!.posLabel)
        assertEquals("сущ., м. р.", engine.entry("mapa")!!.posLabel)
        val c = engine.conjugation("hablar")
        assertEquals(listOf("hablo"), c["наст."]!!["1 ед"])
        assertEquals(listOf("habló"), c["претерит"]!!["3 ед"])
        assertTrue(c["имперфект субх."]!!["1 ед"]!!.containsAll(listOf("hablara", "hablase")))
        assertEquals(listOf("hablando"), c["герундий"]!![""])
        assertEquals(listOf("hablado"), c["причастие"]!![""])
        assertEquals(listOf("fui"), engine.conjugation("ir")["претерит"]!!["1 ед"])
    }

    @Test
    fun spanishVariantPutsItsSensesFirst() {
        assertEquals(0, EsVariant.LATAM.rank("(Лат. Ам.) автомобиль"))
        assertEquals(2, EsVariant.SPAIN.rank("(Мекс., разг.) работа"))
        assertEquals(1, EsVariant.SPAIN.rank("(разг.) парень"))
        assertEquals(1, EsVariant.ANY.rank("(Исп.) компьютер"))
    }

    companion object {
        fun load(): EsEngine {
            val dir = File("src/main/assets/es")
            return EsEngine.load(File(dir, "dict.tsv").inputStream(), File(dir, "forms.tsv").inputStream(), File(dir, "grammar.tsv").inputStream())
        }
    }
}
