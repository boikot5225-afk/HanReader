package com.hanreader.app.ai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import java.io.File

/** JVM AES-GCM storage checks; hardware Android Keystore still needs a device smoke test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiKeyVaultTest {
    @Test fun encryptedKeySurvivesRecreationCanBeReplacedAndDeletedAndIsOutsideBackup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val vault = AiKeyVault(app) { key }
        vault.save("test-secret-for-encryption")
        val path = File(app.noBackupFilesDir, "deepseek-key.aes")
        assertTrue(vault.exists())
        assertFalse(path.readText().contains("test-secret-for-encryption"))
        val reopened = AiKeyVault(app) { key }
        assertEquals("test-secret-for-encryption", reopened.read())
        reopened.save("test-replacement-secret")
        assertEquals("test-replacement-secret", vault.read())
        val bytes = path.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); path.writeBytes(bytes)
        try { vault.read(); fail("GCM must reject tampering") } catch (_: javax.crypto.AEADBadTagException) {}
        vault.delete(); assertFalse(path.exists()); assertNull(vault.read())
        assertFalse(app.getSharedPreferences("context_ai", 0).all.values.any { it.toString().contains("secret") })
    }
    @Test fun freshSettingsNeverEnablePaidCallsOrAutomaticMode() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("context_ai", 0).edit().clear().commit()
        val settings = AiSettings(app)
        assertFalse(settings.config.value.enabled)
        assertFalse(settings.config.value.automatic)
        assertFalse(settings.config.value.consent)
        assertEquals("deepseek-flash", settings.config.value.model)
        settings.update(settings.config.value.copy(enabled = true, consent = false))
        assertFalse(settings.config.value.enabled)
    }
}
