package com.hanreader.app.images

import android.content.Context
import com.hanreader.app.ai.AiFailure
import com.hanreader.app.ai.AiKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cloudflare Workers AI account for card pictures; the token lives in its own encrypted file. */
data class ImageConfig(val accountId: String = "", val hasToken: Boolean = false) {
    val ready get() = accountId.isNotEmpty() && hasToken
}

class ImageSettings(context: Context, val vault: AiKeyVault = AiKeyVault(context, "cloudflare-token.aes", "amauta.cloudflare.v1")) {
    private val prefs = context.getSharedPreferences("card_images", Context.MODE_PRIVATE)
    private fun load() = ImageConfig(prefs.getString("account", "").orEmpty(), vault.exists())
    private val _config = MutableStateFlow(load())
    val config = _config.asStateFlow()
    fun setAccount(id: String) {
        require(id.isEmpty() || validAccount(id))
        prefs.edit().putString("account", id).apply()
        refresh()
    }
    fun refresh() { _config.value = load() }

    companion object {
        fun validAccount(id: String) = id.matches(Regex("[A-Za-z0-9]{16,64}"))
    }
}

/** A refused picture request; [status] tells a rejected description (400/422) from other failures. */
class ImageFailure(val status: Int, message: String) : AiFailure(message)

interface ImageClient {
    suspend fun generate(account: String, token: String, prompt: String, seed: Int): ByteArray
    suspend fun translate(account: String, token: String, text: String): String?
}

/**
 * FLUX.1 [schnell] on Cloudflare Workers AI: `POST …/accounts/{id}/ai/run/@cf/black-forest-labs/flux-1-schnell`
 * with `{prompt, steps}`; the answer is `{"result":{"image":"<base64 JPEG>"},"success":true}`.
 */
class CloudflareImageClient(private val base: String = "https://api.cloudflare.com/client/v4") : ImageClient {
    override suspend fun generate(account: String, token: String, prompt: String, seed: Int): ByteArray =
        // Only the documented fields: an unknown one (seed) makes the model refuse the request with 400.
        parse(post(account, token, MODEL, JSONObject().put("prompt", prompt.take(MAX_PROMPT)).put("steps", STEPS)))

    /** Russian [text] in English through m2m100 on the same account; null when it gives nothing usable. */
    override suspend fun translate(account: String, token: String, text: String): String? {
        val reply = post(account, token, TRANSLATOR, JSONObject().put("text", text.take(300))
            .put("source_lang", "russian").put("target_lang", "english"))
        return runCatching { JSONObject(reply).optJSONObject("result")?.optString("translated_text") }.getOrNull()
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { t -> t.any { it in 'A'..'Z' || it in 'a'..'z' } }
    }

    private suspend fun post(account: String, token: String, model: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        require(ImageSettings.validAccount(account))
        val connection = URL("$base/accounts/$account/ai/run/$model").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status != 200) {
                val reason = connection.errorStream?.use { errorMessage(String(it.readLimited(64_000), Charsets.UTF_8)) }
                throw ImageFailure(status, when (status) {
                    401, 403 -> "Cloudflare не принял токен. Нужен API-токен с правом Workers AI и верный Account ID."
                    404 -> "Cloudflare не нашёл аккаунт или модель. Проверь Account ID."
                    429 -> "Лимит Cloudflare исчерпан. Бесплатные нейроны обновятся завтра."
                    400, 422 -> "Cloudflare отклонил запрос картинки."
                    else -> "Cloudflare временно недоступен (HTTP $status)."
                } + (reason?.let { " Ответ Cloudflare: $it" } ?: ""))
            }
            connection.inputStream.use { String(it.readLimited(MAX_RESPONSE), Charsets.UTF_8) }
        } catch (e: AiFailure) { throw e }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: java.net.SocketTimeoutException) { throw AiFailure("Cloudflare не ответил вовремя. Попробуй ещё раз.") }
        catch (e: Exception) { throw AiFailure("Не удалось получить картинку. Проверь интернет.") }
        finally { connection.disconnect() }
    }

    companion object {
        const val MODEL = "@cf/black-forest-labs/flux-1-schnell"
        const val TRANSLATOR = "@cf/meta/m2m100-1.2b"
        const val STEPS = 4
        const val MAX_PROMPT = 2048
        private const val MAX_RESPONSE = 12_000_000

        /** `errors[0].message` of a Cloudflare error answer, short and on one line; never the request. */
        fun errorMessage(text: String): String? = runCatching {
            val root = JSONObject(text)
            val e = root.optJSONArray("errors")?.optJSONObject(0)
            (e?.optString("message") ?: root.optString("error")).orEmpty()
        }.getOrNull()?.replace(Regex("\\s+"), " ")?.trim()?.take(200)?.takeIf { it.isNotEmpty() }

        /** The picture bytes of a Workers AI answer; anything that is not a JPEG/PNG/WebP is refused. */
        fun parse(text: String): ByteArray {
            val root = JSONObject(text)
            if (root.has("success") && !root.optBoolean("success")) throw AiFailure("Cloudflare вернул ошибку. Попробуй ещё раз.")
            val encoded = root.optJSONObject("result")?.optString("image").orEmpty().ifEmpty { root.optString("image") }
            if (encoded.isEmpty()) throw AiFailure("Cloudflare не вернул картинку.")
            val bytes = try { java.util.Base64.getMimeDecoder().decode(encoded) }
                catch (_: IllegalArgumentException) { throw AiFailure("Cloudflare вернул не картинку.") }
            if (!isImage(bytes)) throw AiFailure("Cloudflare вернул не картинку.")
            return bytes
        }

        fun isImage(b: ByteArray) = b.size > 16 && (
            (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||
            (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()) ||
            (String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP"))

        private fun java.io.InputStream.readLimited(max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            while (true) {
                val n = read(buf); if (n < 0) break
                require(out.size() + n <= max); out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}

/** What to draw: DeepSeek turns the word, its meaning here and the sentence into an English scene; else a template. */
object CardImagePrompt {
    const val STYLE = "Simple clean flat illustration for a vocabulary flashcard, one clear central subject, " +
        "soft colors, plain light background, no text, no letters, no captions, no watermark."

    fun payload(language: String, word: String, reading: String, meaning: String, sentence: String): JSONObject =
        JSONObject().put("task", "image_prompt").put("language", language).put("word", word.take(80))
            .put("reading", reading.take(120)).put("meaning", meaning.take(200)).put("sentence", sentence.take(400))

    /** DeepSeek's `{"prompt":"…"}` with stray Han/Cyrillic dropped; null when no usable English scene is left. */
    fun parse(content: String): String? = runCatching { JSONObject(content).optString("prompt") }.getOrNull()
        ?.filterNot { c -> Character.UnicodeScript.of(c.code).let { it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.CYRILLIC } }
        ?.replace(Regex("""\(\s*\)|"\s*"|“\s*”"""), " ")?.replace(Regex("\\s+"), " ")?.trim()
        ?.takeIf { p -> p.length in 8..600 && p.count { it in 'A'..'Z' || it in 'a'..'z' } >= 8 }

    /** The English part of [text] (FLUX reads only English); null when nothing Latin is there. */
    fun latin(text: String): String? = text.replace(Regex("[^A-Za-z0-9 ,.'-]"), " ").replace(Regex("\\s+"), " ")
        .trim(' ', ',', '.', '-', '\'').take(120).takeIf { t -> t.any { it in 'A'..'Z' || it in 'a'..'z' } }

    /** Without DeepSeek: the meaning in English (already English or translated); never a scene unrelated to the word. */
    fun fallback(english: String): String = "A clear picture that shows the idea \"${english.take(120)}\""

    fun final(scene: String) = "${scene.trimEnd('.', ' ')}. $STYLE".take(CloudflareImageClient.MAX_PROMPT)
}
