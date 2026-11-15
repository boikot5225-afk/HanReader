package com.hanreader.app.en

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnDictionaryMapTest {
    @Test fun copiedAndMemoryMappedAssetsCanBeReopened() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = EnDictionary.load(context).analyses("went")
        assertTrue(first.any { it.entry.word == "go" && it.entry.russian.isNotEmpty() })
        assertTrue(EnDictionary.load(context).analyses("children").any { it.entry.word == "child" })
    }
}
