package com.hanreader.app.zh

import com.hanreader.app.reader.*
import com.hanreader.app.lang.TextOptions
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Original synthetic examples; no complete book or quoted prose is required by CI. */
class ZhContextRegressionTest {
    companion object {
        val engine: ZhEngine by lazy {
            val dir=File("src/main/assets/zh")
            fun lines(name:String)=File(dir,name).readLines().asSequence()
            ZhRules.current=ZhRules.load(File(dir,"context.tsv"))
            ZhEngine(ZhDictionary.map(File(dir,"lexicon.bin")),0,HskLevels.parse(lines("hsk.tsv")),
                ZhEngine.parseRussian(lines("ru.tsv")),ZhEngine.parseRussian(lines("ru_ai.tsv")),
                ZhEngine.parseNames(lines("names.tsv")),File(dir,"script.tsv").inputStream().use { ZhScript.parse(it) },
                ZhEngine.parseRussianChars(lines("ru_chars.tsv")),ZhEngine.parseMeasures(lines("measure.tsv")))
        }
    }
    private fun token(text:String,word:String):RToken = engine.chapter(listOf(text),TextOptions())(text).tokens.first { it.text==word }
    private fun sense(t:RToken)=engine.russianSenses(t.text,t.pinyin,t.contextMeaning).first()

    @Test fun sensesDoNotPollutePronunciationOrOtherOccurrences() {
        repeat(3) {
            val bank=token("妖精看守着银行的金库。","妖精")
            val forest=token("山里的妖精躲在洞穴里。","妖精")
            assertEquals("гоблин",sense(bank))
            assertEquals("нечистая сила",sense(forest))
            assertEquals(bank.pinyin,forest.pinyin)
            assertFalse(bank.pinyin.contains('#'))
            assertNotEquals(bank.glossKey,forest.glossKey)
            assertEquals("нечистая сила",engine.russianSenses("妖精").first())
        }
    }
    @Test fun senseWindowCrossesACommaButNotASentence() {
        assertEquals("гоблин",sense(token("妖精开着小车，穿过银行来到金库。","妖精")))
        assertEquals("нечистая сила",sense(token("妖精躲在山里。银行很远。","妖精")))
    }
    @Test fun aSelectedSenseAgreesAcrossHintCardAndSave() {
        val t=token("妖精在银行工作。","妖精")
        assertEquals("гоблин",engine.glossShort(t))
        assertEquals("гоблин",sense(t))
        assertTrue(engine.cardTranslation(t.text,t.pinyin,t.contextMeaning)!!.startsWith("гоблин"))
    }
    @Test fun respectableBuildingIsDistinctFromDignity() {
        assertTrue(sense(token("那是一栋非常体面的宅邸。","体面")).contains("респектабельный"))
        assertEquals("достоинство",sense(token("他忘记了体面。","体面")))
    }
    @Test fun pixieAndElfRemainDistinct() {
        assertEquals("пикси",sense(token("洛哈特把小精灵带进教室。","小精灵")))
        assertEquals("эльф",sense(token("树林里的小精灵唱着歌。","小精灵")))
    }
    @Test fun enumerationDoesNotMakeTheAdverbialParticleEarth() {
        val a=token("他慢慢地、轻轻地放下杯子。","地")
        assertEquals("de",a.pinyin)
        assertEquals("dì",token("脚下的地，很硬。","地").pinyin)
    }
    @Test fun magicalEntitiesAndSpellsAreWholeDefinedUnits() {
        for((word,ru) in listOf("家养小精灵" to "эльф","摄魂怪" to "дементор","食死徒" to "Пожиратель",
            "魁地奇" to "квиддич","阿瓦达索命" to "Авада","呼神护卫" to "Патронум","统统石化" to "Тоталус",
            "幻影移形" to "аппарация","厄里斯魔镜" to "Еиналеж","复方汤剂" to "оборотное",
            "阿尼马格斯" to "анимаг","老魔杖" to "Бузинная")) {
            val t=token("他看见了${word}。",word)
            assertEquals(word,t.text)
            assertEquals(word.length,t.syllables.size)
            assertTrue(word,engine.cardTranslation(word).orEmpty().contains(ru,true))
        }
    }
    @Test fun traditionalTextUsesTheSameSenseRulesWithoutMovingOffsets() {
        val text="妖精在銀行的金庫前。"
        val t=token(text,"妖精")
        assertEquals("гоблин",sense(t))
        assertEquals("妖精",text.substring(t.start,t.end))
    }
    @Test fun perReadingMeaningsAlsoApplyToMulticharacterWords() {
        val heavy=token("他重重地坐下。","重重")
        assertTrue(heavy.pinyin,heavy.pinyin.startsWith("zhòng"))
        assertTrue(engine.russianSenses(heavy.text,heavy.pinyin,heavy.contextMeaning).first().contains("тяж"))
    }
    @Test fun aCompoundWithoutItsOwnReadingStillKnowsMusic() {
        val text="音乐声从窗外传来。"
        val tokens=engine.chapter(listOf(text),TextOptions())(text).tokens
        val t=tokens.first { '乐' in it.text }
        assertEquals("yuè",t.syllables[t.text.indexOf('乐')])
    }

    @Test fun ageIsNotPhysicalLength() {
        val older=token("他比我长三岁。","长")
        assertEquals("zhǎng",older.pinyin)
        assertTrue(sense(older).startsWith("старше"))
        assertEquals("cháng",token("桌子长三米。","长").pinyin)
    }
    @Test fun literaryBiographyDoesNotReplaceTheMainNarrativeEverywhere() {
        assertEquals("биография; жизнеописание",sense(token("我要给这位老人做正传。","正传")))
        assertEquals("главная тема романа",sense(token("我们回到小说的正传。","正传")))
    }
    @Test fun dictionaryAliasesResolveWithoutCyclesOrContextCaching() {
        assertEquals(engine.russianSenses("光彩"),engine.russianSenses("光采"))
        val cycle=ZhEngine(engine.dictionary,0,russianAi=mapOf("甲" to "вариант 乙","乙" to "вариант 甲"))
        assertEquals(listOf("вариант 乙"),cycle.russianSenses("甲"))
    }
    @Test fun modalZhideHasTheAttestedFullTone() {
        assertEquals("zhǐdé",token("他只得回家。","只得").pinyin)
    }

}
