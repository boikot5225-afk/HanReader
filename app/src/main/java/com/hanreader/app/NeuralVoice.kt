package com.hanreader.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The neural voice: Chinese speech made by the developer's server (Kokoro, an OpenAI-style
 * POST /v1/audio/speech), used for reading aloud and the word sheet instead of the phone's voice
 * when the reader turns it on. Only the text to speak is sent. Answers are kept in the app's
 * cache, so a sentence heard again needs no network.
 */
class NeuralVoice private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("neural_voice", Context.MODE_PRIVATE)
    private val cacheDir = File(context.applicationContext.cacheDir, "voice")
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, MutableList<(Result<File>) -> Unit>>()

    /** The reader turned the neural voice on. */
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false))
        private set
    var voice by mutableStateOf(prefs.getString("voice", null) ?: DEFAULT_VOICE)
        private set
    /** Server address, e.g. https://voice.example.com; empty: not set up. */
    var serverUrl by mutableStateOf(prefs.getString("url", null) ?: BuildConfig.VOICE_URL)
        private set
    var token by mutableStateOf(prefs.getString("token", null) ?: BuildConfig.VOICE_TOKEN)
        private set

    /** On and pointing somewhere. */
    val available: Boolean get() = enabled && serverUrl.isNotBlank()

    fun update(enabled: Boolean, voice: String, serverUrl: String, token: String) {
        this.enabled = enabled
        this.voice = voice
        this.serverUrl = serverUrl.trim().trimEnd('/')
        this.token = token.trim()
        prefs.edit().putBoolean("enabled", enabled).putString("voice", voice)
            .putString("url", this.serverUrl).putString("token", this.token).apply()
    }

    /** The audio of [text] at [speed]: from the cache, else from the server; [done] runs on the main thread. */
    fun fetch(text: String, speed: Float, done: (Result<File>) -> Unit) {
        val url = serverUrl
        val v = voice
        val tok = token
        val file = File(cacheDir, cacheKey(v, speed, text))
        if (file.length() > 0) { file.setLastModified(System.currentTimeMillis()); done(Result.success(file)); return }
        val key = file.name
        val waiting = pending[key]
        if (waiting != null) { waiting += done; return }
        pending[key] = mutableListOf(done)
        executor.execute {
            val result = runCatching {
                cacheDir.mkdirs()
                val tmp = File(cacheDir, "$key.part")
                synthesize(url, tok, v, text, speed, tmp)
                if (!tmp.renameTo(file)) throw IOException("cache")
                trimCache(cacheDir, MAX_CACHE_BYTES)
                file
            }
            main.post { pending.remove(key)?.forEach { it(result) } }
        }
    }

    /** Fetches [text] ahead of time, so it plays without a pause; errors are ignored. */
    fun prefetch(text: String, speed: Float) = fetch(text, speed) { }

    companion object {
        const val DEFAULT_VOICE = "zf_xiaoxiao"
        /** Kokoro v1.0 Mandarin voices: id to a name for the menu. */
        val VOICES = listOf(
            "zf_xiaoxiao" to "Сяосяо · женский",
            "zf_xiaoyi" to "Сяои · женский",
            "zf_xiaobei" to "Сяобэй · женский",
            "zf_xiaoni" to "Сяони · женский",
            "zm_yunxi" to "Юньси · мужской",
            "zm_yunjian" to "Юньцзянь · мужской",
            "zm_yunxia" to "Юнься · мужской",
            "zm_yunyang" to "Юньян · мужской"
        )
        private const val MAX_CACHE_BYTES = 100L * 1024 * 1024
        private const val TIMEOUT_MS = 30_000

        @Volatile private var instance: NeuralVoice? = null
        fun get(context: Context): NeuralVoice =
            instance ?: synchronized(this) { instance ?: NeuralVoice(context).also { instance = it } }

        /** File name of the cached audio of [text] in [voice] at [speed]. */
        fun cacheKey(voice: String, speed: Float, text: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest("$voice|${"%.2f".format(java.util.Locale.ROOT, speed)}|$text".toByteArray())
            return d.joinToString("") { "%02x".format(it) } + ".audio"
        }

        /**
         * Asks the server at [baseUrl] for [text] spoken in [voice] at [speed] and writes the audio to [out].
         * Throws on a network error, an error status or an answer that is not audio.
         */
        fun synthesize(baseUrl: String, token: String, voice: String, text: String, speed: Float, out: File) {
            val body = "{\"model\":\"kokoro\",\"input\":${json(text)},\"voice\":${json(voice)}," +
                "\"speed\":${"%.2f".format(java.util.Locale.ROOT, speed)}}"
            val c = URL("$baseUrl/v1/audio/speech").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                if (code != 200) throw IOException("HTTP $code")
                c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                if (!looksLikeAudio(out)) { out.delete(); throw IOException("not audio") }
            } finally {
                c.disconnect()
            }
        }

        /** [s] as a JSON string literal. */
        fun json(s: String): String = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }

        /** WAV, MP3, OGG, FLAC or AAC by their first bytes; a JSON error page is not audio. */
        fun looksLikeAudio(f: File): Boolean {
            if (f.length() < 64) return false
            val h = ByteArray(4)
            f.inputStream().use { it.read(h) }
            val s = String(h, Charsets.ISO_8859_1)
            return s == "RIFF" || s.startsWith("ID3") || s == "OggS" || s == "fLaC" ||
                (h[0] == 0xFF.toByte() && (h[1].toInt() and 0xE0) == 0xE0)
        }

        /** Removes the least recently used files until [dir] holds at most [max] bytes. */
        fun trimCache(dir: File, max: Long) {
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".audio") } ?: return
            var total = files.sumOf { it.length() }
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= max) break
                total -= f.length()
                f.delete()
            }
        }
    }
}

/** Plays one audio file at a time. */
class AudioPlayer {
    private var player: MediaPlayer? = null

    /** Plays [file]; [onDone] after its end, [onError] if it cannot be played. */
    fun play(file: File, onDone: () -> Unit, onError: () -> Unit) {
        stop()
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(file.path)
            p.setOnCompletionListener { if (player === p) { stop(); onDone() } }
            p.setOnErrorListener { _, _, _ -> if (player === p) { stop(); onError() }; true }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            stop()
            file.delete()
            onError()
        }
    }

    fun stop() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        p.release()
    }
}
