package com.hanreader.app

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString

/**
 * Waits like [ComposeTestRule.waitUntil]; on a timeout the failure says what was on the screen and
 * what the app logged (errors and warnings), so a run on CI explains itself without the device.
 */
fun ComposeTestRule.waitOrExplain(what: String, timeoutMs: Long, condition: () -> Boolean) {
    try {
        waitUntil(timeoutMs, condition)
    } catch (e: ComposeTimeoutException) {
        val screen = runCatching { onRoot(useUnmergedTree = false).printToString(maxDepth = 12) }.getOrElse { "(no screen: $it)" }
        val log = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "brief", "-t", "400", "*:W"))
            p.inputStream.bufferedReader().readLines().takeLast(120).joinToString("\n")
        }.getOrElse { "(no log: $it)" }
        val mem = Runtime.getRuntime().let { "heap used ${(it.totalMemory() - it.freeMemory()) shr 20} MB of max ${it.maxMemory() shr 20} MB" }
        throw AssertionError("Timed out after $timeoutMs ms waiting for: $what\n$mem\n--- screen ---\n$screen\n--- log ---\n$log", e)
    }
}
