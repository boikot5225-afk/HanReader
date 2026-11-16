package com.hanreader.app.ai

import android.content.Context
import androidx.room.withTransaction
import com.hanreader.app.LexiconDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class AiUsage(val requests: Int = 0, val tokens: Long = 0, val unknown: Int = 0)

/** One process session, one paid request at a time, also shared by connection tests. */
class AiRepository(
    private val db: LexiconDatabase,
    private val client: AiClient,
    private val config: () -> AiConfig,
    private val key: () -> String?
) {
    private val mutex = Mutex()
    private val attempts = HashSet<String>()
    private val _usage = MutableStateFlow(AiUsage())
    val usage = _usage.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    fun notice(message: String) { _message.value = message }
    private val dao = db.aiCheckDao()
    fun observe(book: Long) = dao.observe(book)
    fun observeChapter(book: Long, chapter: Int) = dao.observeChapter(book, chapter)

    private suspend fun request(payload: JSONObject, expected: AiConfig): AiReply {
        val c = config()
        if (c != expected || !c.enabled || !c.consent || !c.hasKey) throw AiFailure("Включи Контекстный ИИ и сохрани ключ в настройках.")
        if (_usage.value.requests >= c.limit) throw AiFailure("Достигнут лимит запросов за сеанс. Его можно изменить в настройках.")
        val secret = withContext(Dispatchers.IO) { key() } ?: throw AiFailure("Ключ DeepSeek отсутствует.")
        _usage.value = _usage.value.copy(requests = _usage.value.requests + 1)
        _busy.value = true
        var accounted = false
        try {
            val reply = client.complete(secret, c.model, payload)
            _usage.value = _usage.value.copy(tokens = _usage.value.tokens + (reply.tokens ?: 0),
                unknown = _usage.value.unknown + if (reply.tokens == null) 1 else 0)
            accounted = true
            return reply
        } finally {
            if (!accounted) _usage.value = _usage.value.copy(unknown = _usage.value.unknown + 1)
            _busy.value = false
        }
    }

    suspend fun check(fragment: AiFragment, automatic: Boolean, stillValid: suspend () -> Boolean): List<AiCheck> = mutex.withLock {
        val c = config()
        if (!c.enabled || !c.consent || !c.hasKey || automatic && !c.automatic) return@withLock emptyList()
        val existing = fragment.terms.mapNotNull { dao.get(fragment.key(it, c.model)) }
        val cached = existing.mapTo(HashSet()) { it.cacheKey }
        val pending = fragment.terms.filter { fragment.key(it, c.model) !in cached &&
            (!automatic || fragment.key(it, c.model) !in attempts) }
        if (pending.isEmpty() && existing.isNotEmpty() && !automatic) _message.value = "Показан сохранённый результат. Новый запрос не нужен."
        val out = existing.toMutableList()
        for (batch in pending.chunked(AiContext.BATCH)) {
            currentCoroutineContext().ensureActive()
            if (!stillValid()) break
            val sub = fragment.copy(terms = batch)
            attempts += batch.map { sub.key(it, c.model) }
            try {
                val reply = request(sub.json(), c)
                if (!reply.finished) throw AiFailure("Ответ DeepSeek обрезан. Переводы не сохранены.")
                var rows = try { withContext(Dispatchers.Default) { AiContext.validate(reply.content, sub, c.model) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { throw AiFailure("Некорректный ответ DeepSeek. Переводы не сохранены.") }
                currentCoroutineContext().ensureActive()
                if (config() != c || !stillValid()) break
                val saved = db.withTransaction {
                    if (db.bookDao().get(fragment.book) != null && stillValid()) {
                        val prior = dao.context(fragment.book, fragment.contextHash)
                        rows = rows.map { row ->
                            if (row.translation == null) row else {
                                val overlaps = prior.filter { it.start < row.end && it.end > row.start }
                                when {
                                    overlaps.any { it.state == "rejected" } -> row.copy(state = "rejected")
                                    overlaps.any { it.state == "accepted" && (it.start != row.start || it.end != row.end || it.translation != row.translation) } -> row.copy(state = "proposed")
                                    else -> row
                                }
                            }
                        }
                        dao.put(rows); true
                    } else false
                }
                if (!saved) break
                out += rows
                _message.value = if (rows.any { it.state == "accepted" }) "Контекстные значения сохранены."
                    else if (rows.any { it.state == "proposed" }) "Есть предложение ИИ — открой карточку слова."
                    else "Проверено: перевод сохранён без изменений."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _message.value = (e as? AiFailure)?.userMessage ?: "Не удалось проверить или сохранить значения. Исходный словарь сохранён."
                break // No automatic retry, including after uncertain network failures.
            }
        }
        out
    }

    suspend fun probe() = mutex.withLock {
        try {
            val reply = request(JSONObject().put("task", "Connection check. Return JSON {\"results\":[]}")
                .put("terms", JSONArray()), config())
            if (!reply.finished || JSONObject(reply.content).getJSONArray("results").length() != 0)
                throw AiFailure("Подключение отвечает, но JSON проверки некорректен.")
            _message.value = "Подключение к DeepSeek работает."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _message.value = (e as? AiFailure)?.userMessage ?: "Не удалось проверить подключение." }
    }
    suspend fun setState(key: String, state: String) {
        require(state in setOf("accepted", "rejected"))
        try { db.withTransaction { dao.get(key)?.let {
            if (state == "accepted" && it.translation != null) {
                dao.rejectOverlaps(it.bookId, it.contextHash, it.start, it.end)
                dao.acceptSpan(it.bookId, it.contextHash, it.start, it.end, it.translation)
            } else dao.stateSpan(it.bookId, it.contextHash, it.start, it.end, state)
        } } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { _message.value = "Не удалось сохранить изменение ИИ." }
    }
    suspend fun clear(all: Boolean) = mutex.withLock {
        if (all) dao.clearAll() else dao.clearCache()
        attempts.clear()
    }
}

/** App-lifetime settings and counters; a new process starts a new bounded session. */
class ContextAi internal constructor(context: Context, val settings: AiSettings = AiSettings(context), client: AiClient = DeepSeekClient()) {
    val repository = AiRepository(LexiconDatabase.get(context), client,
        { settings.config.value }, { settings.vault.read() })
    companion object {
        @Volatile private var instance: ContextAi? = null
        fun get(context: Context): ContextAi = instance ?: synchronized(this) {
            instance ?: ContextAi(context.applicationContext).also { instance = it }
        }
    }
}
