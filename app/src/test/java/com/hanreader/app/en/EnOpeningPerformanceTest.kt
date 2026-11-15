package com.hanreader.app.en

import com.hanreader.app.reader.KnownMatcher
import com.hanreader.app.reader.Recommendations
import org.junit.Assert.*
import org.junit.Test

class EnOpeningPerformanceTest {
    @Test fun autoLevelNeverDecodesTheDictionaryOrInspectsWordsWhenNoneAreKnown() {
        // Deliberately invalid article JSON: a scan or an unnecessary lexical lookup fails.
        val dictionary = EnDictionary.read("go\tNOT JSON\n".byteInputStream(), "".byteInputStream(),
            "go\t1\nword\t900\n".byteInputStream())
        val engine = EnEngine(dictionary)
        assertEquals(setOf("go"), engine.levels.knownAt(1))
        assertTrue(engine.levels.knownAt(0).isEmpty())
        assertEquals(1, Recommendations.level(0, KnownMatcher(emptySet(), engine), engine.levels).hsk3)
        assertTrue(dictionary.contains("go"))
    }
}
