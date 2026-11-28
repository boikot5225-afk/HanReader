package com.hanreader.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AiConfig(
    val enabled: Boolean = false,
    val automatic: Boolean = false,
    val consent: Boolean = false,
    val indicators: Boolean = true,
    val model: String = "deepseek-flash",
    /** Paid requests per day. */
    val limit: Int = 100,
    val hasKey: Boolean = false
)

/** The ciphertext is excluded from both Android backup and our explicit book archive. */
class AiKeyVault(context: Context, fileName: String = "deepseek-key.aes", private val alias: String = "amauta.deepseek.v1",
                 private val keySource: (() -> SecretKey)? = null) {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private fun key(): SecretKey {
        keySource?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun exists() = file.baseFile.exists()
    @Synchronized fun read(): String? {
        if (!exists()) return null
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
    @Synchronized fun save(value: String) {
        require(value.length in 8..512 && value.none { it.isWhitespace() || it.isISOControl() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun delete() {
        file.delete()
        if (keySource == null) KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}

class AiSettings(context: Context, val vault: AiKeyVault = AiKeyVault(context)) {
    private val prefs = context.getSharedPreferences("context_ai", Context.MODE_PRIVATE)
    private fun load() = AiConfig(prefs.getBoolean("enabled", false), prefs.getBoolean("auto", false),
        prefs.getBoolean("consent", false), prefs.getBoolean("indicators", true),
        prefs.getString("model", "deepseek-flash")!!, prefs.getInt("daily_limit", 100).coerceIn(1, MAX_LIMIT), vault.exists())
    private val _config = MutableStateFlow(load())
    val config = _config.asStateFlow()
    fun update(value: AiConfig) {
        require(value.model.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
        require(value.limit in 1..MAX_LIMIT)
        prefs.edit().putBoolean("enabled", value.enabled && value.consent).putBoolean("auto", value.automatic)
            .putBoolean("consent", value.consent).putBoolean("indicators", value.indicators)
            .putString("model", value.model).putInt("daily_limit", value.limit).apply()
        _config.value = load()
    }
    fun refresh() { _config.value = load() }

    companion object { const val MAX_LIMIT = 1000 }
}
