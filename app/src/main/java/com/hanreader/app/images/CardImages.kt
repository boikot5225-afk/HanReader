package com.hanreader.app.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hanreader.app.WordCard
import com.hanreader.app.ai.AiFailure
import com.hanreader.app.ai.ContextAi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Card pictures: files/card-images/<card id>.jpg. Backups carry them next to backup.json. */
class CardImageStore(val dir: File) {
    fun file(id: Long) = File(dir, "$id.jpg")
    fun has(id: Long) = file(id).isFile
    fun save(id: Long, bytes: ByteArray) {
        require(CloudflareImageClient.isImage(bytes))
        dir.mkdirs()
        val tmp = File(dir, "$id.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file(id))) { tmp.copyTo(file(id), overwrite = true); tmp.delete() }
        bump()
    }
    fun delete(id: Long) { if (file(id).delete()) bump() }
    /** Card ids with a picture. */
    fun ids(): Set<Long> = dir.listFiles()?.mapNotNullTo(HashSet()) { f -> f.name.removeSuffix(".jpg").takeIf { f.name.endsWith(".jpg") }?.toLongOrNull() }.orEmpty()
    /** A restore: exactly the backup's pictures (card ids now name the backup's cards). */
    fun replaceAll(files: Map<Long, File>) {
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        for ((id, f) in files) if (!f.renameTo(file(id))) f.copyTo(file(id), overwrite = true)
        bump()
    }

    companion object {
        private val _revision = MutableStateFlow(0)
        /** Changes whenever any picture changes, so shown pictures reload. */
        val revision = _revision.asStateFlow()
        private fun bump() { _revision.value++ }
        fun get(context: Context) = CardImageStore(File(context.applicationContext.filesDir, "card-images"))
        const val SIDE = 640

        /** A picture scaled down to at most [SIDE] px and saved as JPEG (FLUX gives 1024 px; photos are larger). */
        fun shrink(bytes: ByteArray): ByteArray {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AiFailure("Это не картинка.")
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw AiFailure("Не удалось прочитать картинку.")
            val scale = SIDE.toFloat() / maxOf(decoded.width, decoded.height)
            val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
            return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        }
    }
}

/** Drawing pictures in the background: a closed sheet does not cancel a paid request. */
class CardPictures private constructor(context: Context, private val client: ImageClient = CloudflareImageClient()) {
    private val app = context.applicationContext
    val settings = ImageSettings(app)
    val store = CardImageStore.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _busy = MutableStateFlow<Set<Long>>(emptySet())
    val busy = _busy.asStateFlow()
    private val _messages = MutableStateFlow<Map<Long, String>>(emptyMap())
    val messages = _messages.asStateFlow()

    private fun note(id: Long, text: String?) { _messages.value = if (text == null) _messages.value - id else _messages.value + (id to text) }

    /** Draws [card]: DeepSeek writes the scene when it is on, else a template; then FLUX draws it. */
    fun draw(card: WordCard, meaning: String = card.translation, sentence: String = card.example) {
        if (card.id in _busy.value) return
        _busy.value = _busy.value + card.id
        note(card.id, null)
        scope.launch {
            try {
                val c = settings.config.value
                if (!c.ready) throw AiFailure("Подключи Cloudflare: Account ID и токен в настройках картинок.")
                val token = withContext(Dispatchers.IO) { settings.vault.read() } ?: throw AiFailure("Токен Cloudflare отсутствует.")
                val ai = ContextAi.get(app)
                val aiOn = ai.settings.config.value.let { it.enabled && it.consent && it.hasKey }
                // Without DeepSeek the meaning itself, in English; a picture of nothing in particular is worse than none.
                suspend fun template(): String {
                    val english = CardImagePrompt.latin(meaning) 
                        ?: try { client.translate(c.accountId, token, meaning)?.let(CardImagePrompt::latin) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                        ?: CardImagePrompt.latin(card.word)
                        ?: throw AiFailure("Не получилось описать картинку по-английски. Включи DeepSeek или выбери фото.")
                    return CardImagePrompt.fallback(english)
                }
                var why: String? = if (aiOn) null else "DeepSeek выключен"
                val scene = if (!aiOn) template() else try {
                    ai.repository.imagePrompt(CardImagePrompt.payload(card.language, card.word, card.reading, meaning, sentence))
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { why = (e as? AiFailure)?.userMessage ?: "DeepSeek не ответил"; template() }
                val bytes = try { client.generate(c.accountId, token, CardImagePrompt.final(scene), 0) }
                    catch (e: ImageFailure) {
                        // A refused description (a filter, an odd word): once more with the plain template.
                        if (e.status != 400 && e.status != 422 || why != null) throw e
                        why = e.userMessage
                        client.generate(c.accountId, token, CardImagePrompt.final(template()), 0)
                    }
                withContext(Dispatchers.IO) { store.save(card.id, CardImageStore.shrink(bytes)) }
                note(card.id, why?.let { "Нарисовано по шаблону, без описания DeepSeek. Причина: ${it.trimEnd('.')}." })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { note(card.id, (e as? AiFailure)?.userMessage ?: "Не удалось нарисовать картинку.") }
            finally { _busy.value = _busy.value - card.id }
        }
    }

    /** A photo from the gallery, scaled down. */
    fun pick(cardId: Long, uri: Uri) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(65_536)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            if (out.size() + n > 30_000_000) throw AiFailure("Фото слишком большое.")
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    } ?: throw AiFailure("Не удалось открыть фото.")
                    store.save(cardId, CardImageStore.shrink(bytes))
                }
                note(cardId, null)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { note(cardId, (e as? AiFailure)?.userMessage ?: "Не удалось взять фото.") }
        }
    }

    fun delete(cardId: Long) { scope.launch(Dispatchers.IO) { store.delete(cardId) } }

    companion object {
        @Volatile private var instance: CardPictures? = null
        fun get(context: Context): CardPictures = instance ?: synchronized(this) {
            instance ?: CardPictures(context.applicationContext).also { instance = it }
        }
    }
}
