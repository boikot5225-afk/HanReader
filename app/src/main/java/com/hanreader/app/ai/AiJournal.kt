package com.hanreader.app.ai

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last answers of DeepSeek the app could not use in full: what was asked, what came back and
 * why it was not taken. Kept only on the phone, never the key; the reader can look, send or delete.
 * It contains fragments of the text being read.
 */
interface AiJournal {
    fun record(model: String, problems: List<AiResponseProblem>, request: String, response: String)
    fun entries(): List<String>
    fun clear()
}

internal fun journalEntry(model: String, problems: List<AiResponseProblem>, request: String, response: String, time: Long): String =
    "Время: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(time))}\n" +
        "Модель: $model\nПричина: ${problems.distinct().joinToString { "${it.name} (${it.description})" }}\n\n" +
        "Запрос:\n${request.take(8_000)}\n\nОтвет:\n${response.take(12_000)}"

internal class MemoryJournal : AiJournal {
    private val list = ArrayList<String>()
    @Synchronized override fun record(model: String, problems: List<AiResponseProblem>, request: String, response: String) {
        list.add(0, journalEntry(model, problems, request, response, System.currentTimeMillis()))
        while (list.size > AI_JOURNAL_MAX) list.removeAt(list.size - 1)
    }
    @Synchronized override fun entries() = list.toList()
    @Synchronized override fun clear() = list.clear()
}

internal class FileJournal(context: Context) : AiJournal {
    private val dir = File(context.noBackupFilesDir, "ai_journal")
    private fun files() = dir.listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.name }.orEmpty()
    @Synchronized override fun record(model: String, problems: List<AiResponseProblem>, request: String, response: String) {
        runCatching {
            dir.mkdirs()
            val now = System.currentTimeMillis()
            File(dir, "%016d.txt".format(now)).writeText(journalEntry(model, problems, request, response, now))
            files().drop(AI_JOURNAL_MAX).forEach { it.delete() }
        }
    }
    @Synchronized override fun entries() = files().mapNotNull { runCatching { it.readText() }.getOrNull() }
    @Synchronized override fun clear() { dir.deleteRecursively() }
}

internal const val AI_JOURNAL_MAX = 30
