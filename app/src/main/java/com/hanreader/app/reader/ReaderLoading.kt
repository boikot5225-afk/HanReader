package com.hanreader.app.reader

import android.os.Build
import android.os.SystemClock
import com.hanreader.app.BuildConfig

enum class ReaderLoadStage(val label: String) {
    FILE("Открываю файл…"), DICTIONARY("Готовлю словарь…"),
    CHAPTER("Готовлю главу…"), PAGES("Разбиваю на страницы…")
}

enum class ReaderLoadFailure(val label: String) {
    FILE("Не удалось открыть книгу."), CHAPTER("Не удалось подготовить главу."),
    PAGES("Не удалось подготовить страницы."), TIMEOUT("Подготовка заняла слишком много времени.")
}

data class ReaderLoadState(
    val stage: ReaderLoadStage,
    val startedAt: Long = SystemClock.elapsedRealtime(),
    val id: Long = System.nanoTime(),
    val failure: ReaderLoadFailure? = null,
    val keepContent: Boolean = false
) {
    fun elapsed(now: Long = SystemClock.elapsedRealtime()) = (now - startedAt).coerceAtLeast(0)
}

const val READER_LOAD_TIMEOUT_MS = 60_000L
const val READER_LOAD_SLOW_MS = 10_000L

/** Only whitelisted technical fields: no book title/path/text, exception messages or API key. */
fun readerLoadReport(state: ReaderLoadState, language: String, now: Long = SystemClock.elapsedRealtime()): String =
    """${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
Android API: ${Build.VERSION.SDK_INT}
Device: ${Build.MANUFACTURER} ${Build.MODEL}
Stage: ${state.stage.name}
Elapsed milliseconds: ${state.elapsed(now)}
Failure: ${state.failure?.name ?: "SLOW"}
Language: ${language.takeIf { it in setOf("es-ES", "en-US", "zh-CN") } ?: "other"}
"""
