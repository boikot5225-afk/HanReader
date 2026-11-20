package com.hanreader.app.reader

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hanreader.app.Appearance
import com.hanreader.app.GlossMode
import com.hanreader.app.ai.*
import com.hanreader.app.lang.LanguageModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch

internal data class WikiVisibleSpan(val block: Int, val start: Int, val end: Int)

internal class WikiAiState(val rows: List<AiCheck>, val enabled: Boolean, val busy: Boolean, val message: String?,
    val check: (Int, IntRange) -> Unit, val state: (AiCheck, String) -> Unit)

@Composable
internal fun rememberWikipediaAi(ai: ContextAi, reading: WikiReading?, engine: LanguageModule?, meanings: Map<String, String>,
    appearance: Appearance, visible: List<WikiVisibleSpan>, blocked: Boolean, baseGlosses: Map<String, String>): WikiAiState {
    val config by ai.settings.config.collectAsState()
    val repo = ai.repository
    val sourceKey = reading?.document?.key
    val enabled = config.enabled && config.consent && config.hasKey && engine != null && AiContext.supports(engine.info.tag)
    val rows by remember(repo, sourceKey, enabled) {
        if (sourceKey == null || !enabled) kotlinx.coroutines.flow.flowOf(emptyList()) else repo.observeArticle(sourceKey).catch { error ->
            if (error is CancellationException) throw error
            repo.notice("Не удалось прочитать кеш ИИ. Исходный словарь сохранён."); emit(emptyList())
        }
    }.collectAsState(initial = emptyList())
    val busy by repo.busy.collectAsState()
    val message by repo.message.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var attached by remember(repo, sourceKey) { mutableStateOf(false) }
    LaunchedEffect(repo, sourceKey, enabled) {
        attached = false
        if (sourceKey != null && enabled) try { repo.attachArticle(sourceKey); attached = true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { repo.notice("Не удалось подготовить кеш ИИ. Словарь доступен без проверки.") }
    }
    val validationKey = remember(reading?.document?.key, reading?.textChapter?.blocks, rows, engine, meanings, appearance.script, appearance.esVariant, config.model, enabled) { Any() }
    val validated by produceState<Pair<Any, List<AiCheck>>?>(null, validationKey) {
        value = validationKey to if (!enabled || reading == null || engine == null) emptyList() else withContext(Dispatchers.Default) {
            WikiAiContext.applicable(reading, engine, meanings, appearance, rows, config.model)
        }
    }
    val valid = validated?.takeIf { it.first === validationKey }?.second.orEmpty()
    val currentReading by rememberUpdatedState(reading)
    val currentMeanings by rememberUpdatedState(meanings)
    val currentAppearance by rememberUpdatedState(appearance)
    val currentLifecycle by rememberUpdatedState(lifecycle)
    val currentVisible by rememberUpdatedState(visible)
    val currentBlocked by rememberUpdatedState(blocked)
    fun stillValid(original: WikiReading, manual: Map<String, String>, a: Appearance, fragment: AiFragment): Boolean =
        currentLifecycle.isAtLeast(Lifecycle.State.RESUMED) && currentReading?.document?.key == original.document.key &&
            currentReading?.textChapter?.blocks?.getOrNull(fragment.block) == original.textChapter.blocks.getOrNull(fragment.block) &&
            currentMeanings == manual && currentAppearance.script == a.script && currentAppearance.esVariant == a.esVariant

    var automaticJob by remember { mutableStateOf<Job?>(null) }
    var manualJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(sourceKey, reading?.textChapter?.blocks, repo, config, meanings, appearance.script, appearance.esVariant, lifecycle) {
        onDispose { automaticJob?.cancel(); manualJob?.cancel(); automaticJob = null; manualJob = null }
    }
    LaunchedEffect(sourceKey, reading, engine, visible, blocked, baseGlosses, config, meanings, appearance.script, appearance.esVariant, appearance.gloss, attached, lifecycle) {
        if (!enabled || !attached || !config.automatic || blocked || appearance.gloss == GlossMode.OFF ||
            reading == null || engine == null || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        delay(1200)
        for (span in visible.distinct().sortedBy { it.block }.take(3)) {
            // Scrolling cancels the waiter; a running paid request may finish and fill the cache.
            automaticJob?.join()
            val fragment = withContext(Dispatchers.Default) { WikiAiContext.automatic(reading, span.block, engine, meanings, appearance, baseGlosses, span.start until span.end) } ?: continue
            val request = scope.launch { try { repo.checkVisible(fragment, canStart = {
                !currentBlocked && currentAppearance.gloss != GlossMode.OFF && currentVisible.any { shown ->
                    shown.block == fragment.block && fragment.terms.any { it.start < shown.end && it.end > shown.start }
                }
            }) { stillValid(reading, meanings, appearance, fragment) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { repo.notice("Не удалось проверить или сохранить значение. Исходный словарь сохранён.") }
            }
            automaticJob = request; request.join()
        }
    }
    return WikiAiState(valid, enabled && attached, busy, message,
        check = { block, range ->
            if (enabled && attached && reading != null && engine != null && manualJob?.isActive != true) {
                manualJob = scope.launch { try {
                    val fragment = withContext(Dispatchers.Default) { WikiAiContext.build(reading, block, engine, meanings, appearance, range = range) }
                    if (fragment == null) repo.notice("Выделение защищено своим переводом или не подходит для контекстной проверки.")
                    else repo.check(fragment, automatic = false) { stillValid(reading, meanings, appearance, fragment) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { repo.notice("Не удалось проверить или сохранить значение. Исходный словарь сохранён.") }
                }
            }
        }, state = { row, state -> scope.launch { repo.setState(row.cacheKey, state) } })
}
