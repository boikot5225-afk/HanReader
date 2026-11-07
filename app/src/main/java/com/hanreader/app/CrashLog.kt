package com.hanreader.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reports kept on the phone (files/crashes), offered for sending on the next start.
 * A report has the time, app and Android versions, the phone model and the error's stack
 * trace: no book text, no words, nothing personal. Nothing leaves the phone unless the
 * reader sends it.
 */
object CrashLog {
    private const val MAX_FILES = 20
    @Volatile private var dir: File? = null
    @Volatile private var header: String = ""

    /** Catches crashes from here on; the system's own handler still runs afterwards (the "app stopped" dialog). */
    fun install(context: Context) {
        val app = context.applicationContext
        dir = File(app.filesDir, "crashes").apply { mkdirs() }
        val version = runCatching {
            val p = app.packageManager.getPackageInfo(app.packageName, 0)
            "${p.versionName} (${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()})"
        }.getOrDefault("?")
        header = "HanReader $version\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Устройство: ${Build.MANUFACTURER} ${Build.MODEL}\n"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write("crash", "Поток: ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** An error the app survived (a book that would not open...), worth knowing about. */
    fun record(where: String, error: Throwable) {
        runCatching { write("error", where, error) }
    }

    private fun write(kind: String, where: String, error: Throwable) {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(now))
        File(d, "$kind-$now.txt").writeText(
            header + "Время: $time\n" + (if (kind == "crash") "Тип: вылет\n" else "Тип: ошибка без вылета\n") + "$where\n\n" + trace.take(30_000)
        )
        d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    /** Saved reports, newest first. */
    fun reports(context: Context): List<File> =
        File(context.applicationContext.filesDir, "crashes").listFiles { f -> f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()

    /** True if the app crashed (not just an error) since the reports were last cleared. */
    fun hasCrash(context: Context): Boolean = reports(context).any { it.name.startsWith("crash-") }

    /** All reports as one text, for sending. */
    fun text(context: Context): String = reports(context).joinToString("\n\n========\n\n") { it.readText() }

    fun clear(context: Context) {
        reports(context).forEach { it.delete() }
    }
}

/** The app process: installs the crash log before anything else runs. */
class HanReaderApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
