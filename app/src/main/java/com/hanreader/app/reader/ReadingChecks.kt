package com.hanreader.app.reader

import android.content.Context
import com.hanreader.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Local, version-scoped counters for a reading trial. No book text, identifiers or network. */
internal object ReadingChecks {
    data class Counts(val opened: Int = 0, val flagged: Int = 0, val corrected: Int = 0, val boundaries: Int = 0) {
        val flagsPer100: Double? get() = if (opened == 0) null else 100.0 * flagged / opened
        fun line(): String = "Карточек: $opened · отметок «Не подходит»: $flagged · исправлений: $corrected · изменений границ: $boundaries"
    }
    enum class Event { OPEN, FLAG, CORRECT, BOUNDARY }
    val counts = MutableStateFlow(Counts())
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir,"reading-checks-${BuildConfig.VERSION_NAME}.json"))
    private val writer=java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task,"reading-checks").apply { isDaemon=true }
    }
    private var loadedFor: String? = null
    @Synchronized fun load(context: Context): Counts {
        val path=file(context).baseFile.path
        if (loadedFor == path) return counts.value
        return runCatching {
            val data = JSONObject(file(context).openRead().bufferedReader().use { it.readText() })
            Counts(data.optInt("opened"),data.optInt("flagged"),data.optInt("corrected"),data.optInt("boundaries"))
        }.getOrDefault(Counts()).also { counts.value = it; loadedFor=path }
    }
    @Synchronized fun record(context: Context, event: Event) {
        val c = load(context)
        save(context, when(event) {
            Event.OPEN -> c.copy(opened=c.opened+1)
            Event.FLAG -> c.copy(flagged=c.flagged+1)
            Event.CORRECT -> c.copy(corrected=c.corrected+1)
            Event.BOUNDARY -> c.copy(boundaries=c.boundaries+1)
        })
    }
    @Synchronized fun reset(context: Context) = save(context,Counts())
    private fun save(context: Context, c: Counts) {
        val target=file(context)
        loadedFor=target.baseFile.path
        counts.value=c
        writer.execute { persist(target,c) }
    }
    /** Used by storage regression checks; never blocks a reading interaction. */
    internal fun awaitWrites() { writer.submit {}.get() }
    private fun persist(target: AtomicFile, c: Counts) {
        var stream: java.io.FileOutputStream? = null
        try {
            val data=JSONObject().put("opened",c.opened).put("flagged",c.flagged)
                .put("corrected",c.corrected).put("boundaries",c.boundaries)
            stream=target.startWrite()
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (_: java.io.IOException) {
            // A diagnostic counter must never prevent the reader from opening a card.
            stream?.let { target.failWrite(it) }
        }
    }
}
