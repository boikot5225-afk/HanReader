package com.hanreader.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files

/** The neural voice client against a local stand-in for the voice server. */
class NeuralVoiceTest {
    private var server: ServerSocket? = null
    private val dir: File = Files.createTempDirectory("voice").toFile()
    @Volatile private var lastBody = ""
    @Volatile private var lastAuth: String? = null

    @After fun tearDown() { server?.close(); dir.deleteRecursively() }

    /** A one-request HTTP server answering with [status] and [answer] (the android.jar has no HttpServer). */
    private fun serve(status: Int, answer: ByteArray): String {
        val s = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        server = s
        thread(isDaemon = true) {
            runCatching {
                s.accept().use { sock ->
                    val input = sock.getInputStream().buffered()
                    val headers = ArrayList<String>()
                    while (true) {
                        val line = StringBuilder()
                        while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) line.append(c.toChar()) }
                        if (line.isEmpty()) break
                        headers += line.toString()
                    }
                    val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    lastAuth = headers.firstOrNull { it.startsWith("Authorization:", true) }?.substringAfter(':')?.trim()
                    lastBody = ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }.toString(Charsets.UTF_8)
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 $status X\r\nContent-Length: ${answer.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(answer)
                    out.flush()
                }
            }
        }
        return "http://127.0.0.1:${s.localPort}"
    }

    private val wav = "RIFF".toByteArray() + ByteArray(200)

    @Test
    fun writesTheAudioAndSendsTextVoiceSpeedAndKey() {
        val url = serve(200, wav)
        val out = File(dir, "a.audio")
        NeuralVoice.synthesize(url, "secret", "zf_xiaoxiao", "他说：\"你好\"。", 0.8f, out)
        assertEquals(wav.size.toLong(), out.length())
        assertTrue(lastBody.contains("\"input\":\"他说：\\\"你好\\\"。\""))
        assertTrue(lastBody.contains("\"voice\":\"zf_xiaoxiao\""))
        assertTrue(lastBody.contains("\"speed\":0.80"))
        assertEquals("Bearer secret", lastAuth)
    }

    @Test
    fun noKeyNoHeader() {
        val url = serve(200, wav)
        NeuralVoice.synthesize(url, "", "zm_yunxi", "好", 1f, File(dir, "b.audio"))
        assertEquals(null, lastAuth)
    }

    @Test(expected = IOException::class)
    fun errorStatusFails() {
        NeuralVoice.synthesize(serve(401, "{\"detail\":\"no\"}".toByteArray()), "", "zf_xiaoxiao", "好", 1f, File(dir, "c.audio"))
    }

    @Test
    fun anAnswerThatIsNotAudioFailsAndLeavesNoFile() {
        val url = serve(200, ("{\"error\":\"" + "x".repeat(100) + "\"}").toByteArray())
        val out = File(dir, "d.audio")
        assertTrue(runCatching { NeuralVoice.synthesize(url, "", "zf_xiaoxiao", "好", 1f, out) }.isFailure)
        assertFalse(out.exists())
    }

    @Test
    fun cacheKeyDependsOnVoiceSpeedAndText() {
        val k = NeuralVoice.cacheKey("zf_xiaoxiao", 1f, "你好")
        assertEquals(k, NeuralVoice.cacheKey("zf_xiaoxiao", 1.0f, "你好"))
        assertNotEquals(k, NeuralVoice.cacheKey("zm_yunxi", 1f, "你好"))
        assertNotEquals(k, NeuralVoice.cacheKey("zf_xiaoxiao", 1.2f, "你好"))
        assertNotEquals(k, NeuralVoice.cacheKey("zf_xiaoxiao", 1f, "你们好"))
    }

    @Test
    fun cacheDropsTheOldestFirst() {
        val files = (0 until 4).map { i ->
            File(dir, "$i.audio").apply { writeBytes(ByteArray(100)); setLastModified(1_000_000L + i * 1000) }
        }
        NeuralVoice.trimCache(dir, 250)
        assertEquals(listOf(false, false, true, true), files.map { it.exists() })
    }

    @Test
    fun jsonEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", NeuralVoice.json("a\"b\\c\nd"))
    }
}
