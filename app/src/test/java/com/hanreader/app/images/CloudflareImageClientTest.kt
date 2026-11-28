package com.hanreader.app.images

import com.hanreader.app.ai.AiFailure
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64

class CloudflareImageClientTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(30) { it.toByte() }

    /** One canned HTTP answer per request over a socket (no com.sun.net.httpserver on the Android classpath). */
    private fun serve(code: Int, body: String, seen: MutableList<String>): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                    val head = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    val length = head.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val chars = CharArray(length); var read = 0
                    while (read < length) { val n = input.read(chars, read, length - read); if (n < 0) break; read += n }
                    seen += head.joinToString("\n") + "\n\n" + String(chars, 0, read)
                    val bytes = body.toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    @Test fun drawsWithTheAccountTokenAndPromptAndExplainsFailures() = runBlocking {
        val account = "0123456789abcdef0123456789abcdef"
        val seen = mutableListOf<String>()
        val ok = serve(200, JSONObject().put("success", true).put("result", JSONObject().put("image", Base64.getEncoder().encodeToString(jpeg))).toString(), seen)
        try {
            val bytes = CloudflareImageClient("http://127.0.0.1:${ok.localPort}").generate(account, "secret-token", "a red bank building", 7)
            assertArrayEquals(jpeg, bytes)
            val request = seen.single()
            assertTrue(request, request.startsWith("POST /accounts/$account/ai/run/@cf/black-forest-labs/flux-1-schnell "))
            assertTrue(request.contains("Authorization: Bearer secret-token"))
            val body = JSONObject(request.substringAfter("\n\n"))
            assertEquals("a red bank building", body.getString("prompt"))
            assertEquals(CloudflareImageClient.STEPS, body.getInt("steps"))
            assertEquals(7, body.getInt("seed"))
        } finally { ok.close() }
        val denied = serve(401, "{}", mutableListOf())
        try {
            CloudflareImageClient("http://127.0.0.1:${denied.localPort}").generate(account, "bad-token", "x", 1)
            fail()
        } catch (e: AiFailure) { assertTrue(e.userMessage.contains("токен")) } finally { denied.close() }
    }

    @Test fun onlyPicturesAndEnglishScenesAreAccepted() {
        try { CloudflareImageClient.parse("""{"success":true,"result":{"image":"${Base64.getEncoder().encodeToString("<html>not an image</html>".toByteArray())}"}}"""); fail() }
        catch (e: AiFailure) { assertTrue(e.userMessage.contains("не картинку")) }
        try { CloudflareImageClient.parse("""{"success":false,"errors":[{"message":"x"}]}"""); fail() } catch (_: AiFailure) {}
        assertEquals("A man at a bank counter", CardImagePrompt.parse("""{"prompt":"  A man at a bank   counter "}"""))
        assertNull(CardImagePrompt.parse("""{"prompt":"Человек в банке"}"""))
        assertNull(CardImagePrompt.parse("not json"))
        assertTrue(CardImagePrompt.final("A bank.").startsWith("A bank. Simple clean flat illustration"))
    }
}
