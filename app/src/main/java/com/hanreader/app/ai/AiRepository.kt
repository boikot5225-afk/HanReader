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

internal data class AiWordLocation(
    val source: String, val block: Int, val start: Int, val end: Int, val model: String
) {
    fun covers(word: AiWordLocation): Boolean =
        source == word.source && block == word.block && model == word.model && start <= word.start && end >= word.end

    companion object {
        fun book(id: Long, chapter: Int, block: Int, start: Int, end: Int, model: String) =
            AiWordLocation("book:$id:$chapter", block, start, end, model)
    }
}

private fun AiFragment.wordLocations(model: String) = terms.map { term ->
    val article = articleKey
    if (article != null) AiWordLocation(article, block, term.start, term.end, model)
    else AiWordLocation.book(book, chapter, block, term.start, term.end, model)
}

internal data class AiWordFeedback(
    val checking: List<AiWordLocation> = emptyList(),
    val failures: Map<AiWordLocation, String> = emptyMap()
) {
    fun isChecking(word: AiWordLocation) = checking.any { it.covers(word) }
    fun error(word: AiWordLocation) = failures[word] ?: failures.entries.lastOrNull { it.key.covers(word) }?.value
}

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
    private val _wordFeedback = MutableStateFlow(AiWordFeedback())
    internal val wordFeedback = _wordFeedback.asStateFlow()
    fun notice(message: String) { _message.value = message }
    internal fun noticeWord(word: AiWordLocation, message: String) = noticeWords(listOf(word), message)
    private fun noticeWords(words: List<AiWordLocation>, message: String) {
        _wordFeedback.update { current ->
            val failures = LinkedHashMap(current.failures)
            for (word in words) {
                failures.remove(word)
                failures[word] = message
            }
            current.copy(failures = failures.entries.toList().takeLast(200).associate { it.key to it.value })
        }
        notice(message)
    }
    private val dao = db.aiCheckDao()
    fun observeArticle(key: String) = dao.observeArticle(key)
    suspend fun attachArticle(key: String) {
        require(key.matches(Regex("v1:(es|en|zh):[1-9][0-9]*:[1-9][0-9]*")))
        dao.attachArticle(AiArticle(key))
    }
    private suspend fun exists(fragment: AiFragment): Boolean = fragment.articleKey?.let { dao.hasArticle(it) }
        ?: (db.bookDao().get(fragment.book) != null)
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

    suspend fun check(fragment: AiFragment, automatic: Boolean, stillValid: suspend () -> Boolean): List<AiCheck> =
        checkInternal(fragment, automatic, stillValid, stillValid)

    /** Recheck viewport eligibility after waiting for another paid request, before billing. */
    suspend fun checkVisible(fragment: AiFragment, canStart: suspend () -> Boolean, allowMissing: Boolean = false,
        stillValid: suspend () -> Boolean): List<AiCheck> =
        checkInternal(fragment, true, canStart, stillValid, allowMissing)

    private suspend fun checkInternal(fragment: AiFragment, automatic: Boolean, canStart: suspend () -> Boolean,
        stillValid: suspend () -> Boolean, allowMissing: Boolean = false): List<AiCheck> = mutex.withLock {
        fragment.articleKey?.let {
            require(fragment.book == 0L && it.split(':').getOrNull(1) == fragment.language.substringBefore('-') && fragment.resource.startsWith("wikipedia:$it:"))
        }
        val c = config()
        if (!exists(fragment)) return@withLock emptyList()
        if (!c.enabled || !c.consent || !c.hasKey || automatic && !c.automatic) return@withLock emptyList()
        val cached = fragment.terms.mapNotNull { term ->
            val key = fragment.key(term, c.model)
            val legacy = fragment.key(term, c.model, legacy = true)
            val row = dao.get(key) ?: if (legacy != key) dao.get(legacy)?.takeIf { it.state != "checked" } else null
            row?.let { key to it }
        }.toMap()
        val existing = cached.values
        val uncached = fragment.terms.filter { fragment.key(it, c.model) !in cached &&
            (!automatic || fragment.key(it, c.model) !in attempts) }
        val pending = if (automatic && allowMissing) {
            val missing = uncached.filter { it.base.isBlank() }.take(4)
            uncached.filter { it.base.isNotBlank() }.take(AiContext.BATCH - missing.size) + missing
        } else uncached.filter { !automatic || it.base.isNotBlank() }
        if (pending.isEmpty() && existing.isNotEmpty() && !automatic) _message.value = "Показан сохранённый результат. Новый запрос не нужен."
        if (pending.isNotEmpty()) _message.value = null
        val out = existing.toMutableList()
        for (batch in pending.chunked(AiContext.BATCH)) {
            currentCoroutineContext().ensureActive()
            if (!exists(fragment) || !canStart() || !stillValid()) break
            val sub = fragment.copy(terms = batch)
            attempts += batch.map { sub.key(it, c.model) }
            val locations = sub.wordLocations(c.model)
            _wordFeedback.update { it.copy(checking = locations, failures = it.failures - locations.toSet()) }
            try {
                val reply = request(sub.json(), c)
                if (!reply.finished) throw AiFailure("Ответ DeepSeek обрезан. Переводы не сохранены.")
                var rows = try { withContext(Dispatchers.Default) { AiContext.validate(reply.content, sub, c.model) } }
                    catch (e: CancellationException) { throw e }
                    catch (e: AiResponseFailure) {
                        throw AiFailure("Ответ DeepSeek отклонён: ${e.problem.description} [${e.problem.name}]. Переводы не сохранены.")
                    }
                    catch (_: Exception) { throw AiFailure("Некорректный ответ DeepSeek. Переводы не сохранены.") }
                currentCoroutineContext().ensureActive()
                if (config() != c || !stillValid()) break
                val saved = db.withTransaction {
                    if (exists(fragment) && stillValid()) {
                        val prior = dao.context(fragment.book.takeIf { fragment.articleKey == null }, fragment.contextHash, fragment.articleKey)
                        rows = rows.map { row ->
                            if (row.translation == null && row.pinyin == null) row else {
                                val overlaps = prior.filter { it.start < row.end && it.end > row.start }
                                when {
                                    overlaps.any { it.state == "rejected" } -> row.copy(state = "rejected")
                                    overlaps.any { it.state == "accepted" && (it.start != row.start || it.end != row.end ||
                                        it.translation != row.translation || it.pinyin != row.pinyin) } -> row.copy(state = "proposed")
                                    else -> row
                                }
                            }
                        }
                        dao.put(rows); true
                    } else false
                }
                if (!saved) break
                out += rows
                _message.value = if (rows.any { it.state == "accepted" && it.pinyin != null }) "Контекстное чтение сохранено."
                    else if (rows.any { it.state == "accepted" }) "Контекстные значения сохранены."
                    else if (rows.any { it.state == "proposed" }) "Есть предложение ИИ — открой карточку слова."
                    else if (rows.any { it.base.isEmpty() }) AI_UNKNOWN_CHECKED_MESSAGE
                    else AI_UNCHANGED_MESSAGE
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                noticeWords(locations, (e as? AiFailure)?.userMessage
                    ?: "Не удалось проверить или сохранить значения. Исходный словарь сохранён.")
                break // No automatic retry, including after uncertain network failures.
            } finally {
                _wordFeedback.update { it.copy(checking = emptyList()) }
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
            if (state == "accepted" && (it.translation != null || it.pinyin != null)) {
                dao.rejectOverlaps(it.bookId, it.contextHash, it.start, it.end, it.articleKey)
                dao.acceptSpan(it.bookId, it.contextHash, it.start, it.end, it.translation, it.articleKey, it.pinyin)
            } else dao.stateSpan(it.bookId, it.contextHash, it.start, it.end, state, it.articleKey)
        } } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { _message.value = "Не удалось сохранить изменение ИИ." }
    }
    suspend fun clear(all: Boolean) = mutex.withLock {
        if (all) dao.clearAll() else dao.clearCache()
        attempts.clear()
        _wordFeedback.value = AiWordFeedback()
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
