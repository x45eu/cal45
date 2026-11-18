package eu.x45.cal45.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local device credential storage. The AES key stays in AndroidKeyStore.
 * SharedPreferences holds only encrypted app-password bytes and GCM IV.
 * Backups are disabled in AndroidManifest.xml.
 */
class CredentialVault(private val context: Context) {
    private val prefs = context.getSharedPreferences("cal45_account_v1", Context.MODE_PRIVATE)
    private val alias = "cal45_app_password_v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = store.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(server: String, username: String, appPassword: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(appPassword.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit()
            .putString("server", server)
            .putString("username", username)
            .putString("iv", iv)
            .putString("ciphertext", data)
            .commit()) { "Lokale Kontodaten konnten nicht gespeichert werden." }
    }

    fun configured(): Boolean =
        prefs.contains("server") && prefs.contains("username") && prefs.contains("ciphertext")

    fun server(): String = prefs.getString("server", "") ?: ""
    fun username(): String = prefs.getString("username", "") ?: ""

    // Reserved for the future sync adapter. Never place this value in UI/logs.
    fun readAppPassword(): String? {
        val rawIv = prefs.getString("iv", null) ?: return null
        val rawData = prefs.getString("ciphertext", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, Base64.decode(rawIv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(rawData, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun remove() {
        prefs.edit().clear().commit()
        // Keep the key to support safe re-enrollment; old ciphertext is deleted.
    }
}
