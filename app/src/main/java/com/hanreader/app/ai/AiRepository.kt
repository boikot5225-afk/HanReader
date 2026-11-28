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

/** Requests and tokens of one day ([day]: epoch day); the limit is per day. */
data class AiUsage(val requests: Int = 0, val tokens: Long = 0, val unknown: Int = 0, val day: Long = 0)

/** Where the day's usage lives between launches of the app. */
interface AiUsageStore { fun load(): AiUsage; fun save(usage: AiUsage) }

private class MemoryUsage : AiUsageStore {
    private var value = AiUsage()
    override fun load() = value
    override fun save(usage: AiUsage) { value = usage }
}

private class PrefsUsage(context: Context) : AiUsageStore {
    private val prefs = context.getSharedPreferences("context_ai_usage", Context.MODE_PRIVATE)
    override fun load() = AiUsage(prefs.getInt("requests", 0), prefs.getLong("tokens", 0), prefs.getInt("unknown", 0), prefs.getLong("day", 0))
    override fun save(usage: AiUsage) {
        prefs.edit().putInt("requests", usage.requests).putLong("tokens", usage.tokens)
            .putInt("unknown", usage.unknown).putLong("day", usage.day).apply()
    }
}

/** A paragraph of an automatic check: may it start (still on screen), may its answer be kept. */
class AiVisibleCheck(val fragment: AiFragment, val canStart: suspend () -> Boolean, val stillValid: suspend () -> Boolean)

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

/** One paid request at a time, also shared by connection tests; a daily limit of requests. */
class AiRepository(
    private val db: LexiconDatabase,
    private val client: AiClient,
    private val config: () -> AiConfig,
    private val key: () -> String?,
    private val usageStore: AiUsageStore = MemoryUsage(),
    private val today: () -> Long = { java.time.LocalDate.now().toEpochDay() },
    private val explanations: AiExplainCache = MemoryExplainCache(),
    val journal: AiJournal = MemoryJournal()
) {
    private val mutex = Mutex()
    private val attempts = HashSet<String>()
    private val _usage = MutableStateFlow(usageStore.load().let { if (it.day == today()) it else AiUsage(day = today()) })
    val usage = _usage.asStateFlow()
    private fun setUsage(value: AiUsage) { _usage.value = value; usageStore.save(value) }
    /** A new day starts with an empty counter. */
    fun refreshUsage() { if (_usage.value.day != today()) setUsage(AiUsage(day = today())) }
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
        refreshUsage()
        if (_usage.value.requests >= c.limit) throw AiFailure(AI_LIMIT_MESSAGE)
        val secret = withContext(Dispatchers.IO) { key() } ?: throw AiFailure("Ключ DeepSeek отсутствует.")
        setUsage(_usage.value.copy(requests = _usage.value.requests + 1))
        _busy.value = true
        var accounted = false
        try {
            val reply = client.complete(secret, c.model, payload)
            setUsage(_usage.value.copy(tokens = _usage.value.tokens + (reply.tokens ?: 0),
                unknown = _usage.value.unknown + if (reply.tokens == null) 1 else 0))
            accounted = true
            return reply
        } finally {
            if (!accounted) setUsage(_usage.value.copy(unknown = _usage.value.unknown + 1))
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
        val c = config()
        if (!allowed(fragment, c, automatic)) return@withLock emptyList()
        val (existing, pending) = pending(fragment, c, automatic, allowMissing)
        if (pending.isEmpty() && existing.isNotEmpty() && !automatic) _message.value = "Показан сохранённый результат. Новый запрос не нужен."
        if (pending.isNotEmpty()) _message.value = null
        val out = existing.toMutableList()
        for (batch in pending.chunked(AiContext.BATCH)) {
            currentCoroutineContext().ensureActive()
            if (!exists(fragment) || !canStart() || !stillValid()) break
            out += send(listOf(AiVisibleCheck(fragment.copy(terms = batch), canStart, stillValid)), c) ?: break
        }
        out
    }

    /**
     * Automatic check of the paragraphs on screen: their words go together, up to
     * [AiContext.BATCH] per request, so a page usually costs one request instead of one per paragraph.
     */
    suspend fun checkVisibleGroup(items: List<AiVisibleCheck>): Unit = mutex.withLock {
        val c = config()
        val parts = items.flatMap { item ->
            if (!allowed(item.fragment, c, automatic = true)) return@flatMap emptyList()
            pending(item.fragment, c, automatic = true, allowMissing = true).second.chunked(AiContext.BATCH)
                .map { AiVisibleCheck(item.fragment.copy(terms = it), item.canStart, item.stillValid) }
        }
        if (parts.isNotEmpty()) _message.value = null
        val groups = ArrayList<MutableList<AiVisibleCheck>>()
        var size = 0
        for (part in parts) {
            if (groups.isEmpty() || size + part.fragment.terms.size > AiContext.BATCH) { groups += ArrayList<AiVisibleCheck>(); size = 0 }
            groups.last() += part; size += part.fragment.terms.size
        }
        for (group in groups) {
            currentCoroutineContext().ensureActive()
            val ready = group.filter { exists(it.fragment) && it.canStart() && it.stillValid() }
            if (ready.isEmpty()) continue
            send(ready, c) ?: break
        }
    }

    private suspend fun allowed(fragment: AiFragment, c: AiConfig, automatic: Boolean): Boolean {
        fragment.articleKey?.let {
            require(fragment.book == 0L && it.split(':').getOrNull(1) == fragment.language.substringBefore('-') && fragment.resource.startsWith("wikipedia:$it:"))
        }
        return exists(fragment) && c.enabled && c.consent && c.hasKey && (!automatic || c.automatic)
    }

    /** Saved answers for [fragment], and the words still to send. */
    private suspend fun pending(fragment: AiFragment, c: AiConfig, automatic: Boolean, allowMissing: Boolean): Pair<List<AiCheck>, List<AiTerm>> {
        val cached = fragment.terms.mapNotNull { term ->
            val key = fragment.key(term, c.model)
            val legacy = fragment.key(term, c.model, legacy = true)
            val row = dao.get(key) ?: if (legacy != key) dao.get(legacy)?.takeIf { it.state != "checked" } else null
            row?.let { key to it }
        }.toMap()
        val uncached = fragment.terms.filter { fragment.key(it, c.model) !in cached &&
            (!automatic || fragment.key(it, c.model) !in attempts) }
        val pending = if (automatic && allowMissing) {
            val missing = uncached.filter { it.base.isBlank() }.take(4)
            uncached.filter { it.base.isNotBlank() }.take(AiContext.BATCH - missing.size) + missing
        } else uncached.filter { !automatic || it.base.isNotBlank() }
        return cached.values.toList() to pending
    }

    /** What one request gave: the saved rows, what was wrong, and the words worth asking again. */
    private class Round(val out: List<AiCheck>, val problems: List<AiResponseProblem>, val again: List<AiVisibleCheck>)

    /** One paid request for [parts]; [reason] tells the model what was wrong with its previous answer. */
    private suspend fun round(parts: List<AiVisibleCheck>, c: AiConfig, reason: AiResponseProblem?): Round {
        val payload = (if (parts.size == 1) parts[0].fragment.json() else AiContext.groupJson(parts.map { it.fragment }))
            .apply { reason?.let { put("retry_reason", "Предыдущий ответ не принят: ${it.description}. Ответь строго по формату.") } }
        val reply = request(payload, c)
        if (!reply.finished) throw AiFailure("Ответ DeepSeek обрезан. Переводы не сохранены.")
        val problems = ArrayList<AiResponseProblem>()
        val again = ArrayList<AiVisibleCheck>()
        val out = ArrayList<AiCheck>()
        val contents = try {
            if (parts.size == 1) listOf(reply.content) else AiContext.splitGroup(reply.content, parts.size)
        } catch (e: AiResponseFailure) {
            journal.record(c.model, listOf(e.problem), payload.toString(), reply.content)
            return Round(emptyList(), listOf(e.problem), parts)
        }
        for ((i, part) in parts.withIndex()) {
            val parsed = try { withContext(Dispatchers.Default) { AiContext.read(contents[i], part.fragment, c.model) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    problems += (e as? AiResponseFailure)?.problem ?: AiResponseProblem.FORMAT
                    again += part; continue
                }
            problems += parsed.problems
            if (parsed.rejected.isNotEmpty()) again += AiVisibleCheck(part.fragment.copy(terms = parsed.rejected), part.canStart, part.stillValid)
            currentCoroutineContext().ensureActive()
            if (config() != c || !part.stillValid()) continue
            out += save(part.fragment, parsed.rows, part.stillValid) ?: continue
        }
        if (problems.isNotEmpty()) journal.record(c.model, problems, payload.toString(), reply.content)
        return Round(out, problems, again)
    }

    /**
     * Kept: every valid answer of a paragraph still in place; a wrong answer for some words does not
     * throw away the others. Words whose answer was unusable are asked once more, with the reason —
     * at most one extra request, within the daily limit. Network failures are never repeated.
     * Null: nothing was kept (failure, or the text changed), and the caller stops.
     */
    private suspend fun send(parts: List<AiVisibleCheck>, c: AiConfig): List<AiCheck>? {
        for (part in parts) attempts += part.fragment.terms.map { part.fragment.key(it, c.model) }
        val locations = parts.flatMap { it.fragment.wordLocations(c.model) }
        _wordFeedback.update { it.copy(checking = locations, failures = it.failures - locations.toSet()) }
        try {
            val first = round(parts, c, null)
            val out = first.out.toMutableList()
            var problems = first.problems
            var again = first.again
            if (again.isNotEmpty()) {
                val ready = again.filter { exists(it.fragment) && it.stillValid() }
                if (ready.isNotEmpty() && config() == c) try {
                    val second = round(ready, c, problems.firstOrNull() ?: AiResponseProblem.TERMS)
                    out += second.out; problems = second.problems; again = second.again
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The first answer stays; the rest keeps its dictionary value. */ }
            }
            val rejected = again.flatMap { it.fragment.wordLocations(c.model) }
            if (out.isEmpty()) {
                if (problems.isNotEmpty() || rejected.isNotEmpty()) throw rejectedReply(problems.firstOrNull() ?: AiResponseProblem.TERMS)
                return null
            }
            if (rejected.isNotEmpty()) noticeWords(rejected, unclearWord(problems.firstOrNull() ?: AiResponseProblem.TERMS))
            _message.value = if (rejected.isNotEmpty()) "Для ${rejected.size} из ${locations.size} слов DeepSeek не дал " +
                    "надёжного ответа — у них оставлен словарь. Остальное сохранено."
                else if (out.any { it.state == "accepted" && it.pinyin != null }) "Контекстное чтение сохранено."
                else if (out.any { it.state == "accepted" }) "Контекстные значения сохранены."
                else if (out.any { it.state == "proposed" }) "Есть предложение ИИ — открой карточку слова."
                else if (out.any { it.base.isEmpty() }) AI_UNKNOWN_CHECKED_MESSAGE
                else AI_UNCHANGED_MESSAGE
            return out
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            noticeWords(locations, (e as? AiFailure)?.userMessage
                ?: "Не удалось проверить или сохранить значения. Исходный словарь сохранён.")
            return null // No automatic retry after network failures, including uncertain ones.
        } finally {
            _wordFeedback.update { it.copy(checking = emptyList()) }
        }
    }

    private fun unclearWord(p: AiResponseProblem) = "DeepSeek не дал надёжного ответа — оставлен словарь. Можно уточнить ещё раз. [${p.name}]"
    private fun rejectedReply(p: AiResponseProblem) = AiFailure("DeepSeek не дал надёжного ответа — оставлен словарь. " +
        "Переводы не сохранены, можно уточнить ещё раз. [${p.name}]")

    /** Stores [rows] unless the text changed meanwhile; earlier choices of the reader win. */
    private suspend fun save(fragment: AiFragment, rows: List<AiCheck>, stillValid: suspend () -> Boolean): List<AiCheck>? {
        var kept = rows
        val saved = db.withTransaction {
            if (exists(fragment) && stillValid()) {
                val prior = dao.context(fragment.book.takeIf { fragment.articleKey == null }, fragment.contextHash, fragment.articleKey)
                kept = rows.map { row ->
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
                dao.put(kept); true
            } else false
        }
        return if (saved) kept else null
    }

    /** A saved explanation for [payload] (see [AiExplain.payload]); free, no request. */
    suspend fun savedExplanation(payload: JSONObject): AiExplanation? = withContext(Dispatchers.IO) {
        explanations.get(AiExplain.key(config().model, payload))?.let {
            try { AiExplain.parse(it, payload.getString("text")) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
    }

    /** One paid request, once per fragment: the answer is kept and shown again for free. */
    suspend fun explain(payload: JSONObject): AiExplanation = mutex.withLock {
        val c = config()
        savedExplanation(payload)?.let { return@withLock it }
        val reply = request(payload, c)
        if (!reply.finished) throw AiFailure("Ответ DeepSeek обрезан. Выдели фрагмент короче.")
        val explanation = try { withContext(Dispatchers.Default) { AiExplain.parse(reply.content, payload.getString("text")) } }
            catch (e: AiResponseFailure) {
                journal.record(c.model, listOf(e.problem), payload.toString(), reply.content)
                throw AiFailure("DeepSeek не дал понятного объяснения. Попробуй ещё раз. [${e.problem.name}]")
            }
        withContext(Dispatchers.IO) { explanations.put(AiExplain.key(c.model, payload), AiExplain.json(explanation).toString()) }
        explanation
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
        withContext(Dispatchers.IO) { explanations.clear() }
        attempts.clear()
        _wordFeedback.value = AiWordFeedback()
    }
}

/** App-lifetime settings and counters; a new process starts a new bounded session. */
class ContextAi internal constructor(context: Context, val settings: AiSettings = AiSettings(context), client: AiClient = DeepSeekClient()) {
    val repository = AiRepository(LexiconDatabase.get(context), client,
        { settings.config.value }, { settings.vault.read() }, PrefsUsage(context),
        explanations = FileExplainCache(context), journal = FileJournal(context))
    companion object {
        @Volatile private var instance: ContextAi? = null
        fun get(context: Context): ContextAi = instance ?: synchronized(this) {
            instance ?: ContextAi(context.applicationContext).also { instance = it }
        }
    }
}
