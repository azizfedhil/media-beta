package com.mediahub.pear.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets on Android (spec: `Keychain.swift`). Each value is encrypted with an AES-256 key kept in the Android Keystore,
 * which is hardware-backed where the device has it. The key cannot be exported, and the app's backup is off
 * (`allowBackup=false`), so the ciphertext is of no use anywhere else.
 *
 * If the Keystore cannot make or use the key, values go into a private preferences file instead, as the desktop's file
 * fallback does. [set] then returns false.
 */
class AndroidSecretStore(context: Context) : SecretStore {
    private val encryptedPrefs: SharedPreferences = context.getSharedPreferences(ENCRYPTED_PREFS, Context.MODE_PRIVATE)
    private val plainPrefs: SharedPreferences = context.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
    private val secretKey: SecretKey? by lazy { loadOrCreateKey() }

    override val backend: String
        get() = if (secretKey != null) "Android Keystore" else "file only (fallback)"

    @Synchronized
    override fun set(key: String, value: String): Boolean {
        val k = secretKey
        if (k != null) {
            val sealed = runCatching { seal(k, value.toByteArray(StandardCharsets.UTF_8)) }.getOrNull()
            if (sealed != null) {
                encryptedPrefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
                plainPrefs.edit().remove(key).commit()
                return true
            }
        }
        plainPrefs.edit().putString(key, value).commit()
        encryptedPrefs.edit().remove(key).commit()
        return false
    }

    @Synchronized
    override fun get(key: String): String? {
        val stored = encryptedPrefs.getString(key, null)
        if (stored != null) {
            val k = secretKey ?: return null
            val blob = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
            return runCatching { open(k, blob) }.getOrNull()?.toString(StandardCharsets.UTF_8)
        }
        return plainPrefs.getString(key, null)
    }

    @Synchronized
    override fun remove(key: String) {
        encryptedPrefs.edit().remove(key).commit()
        plainPrefs.edit().remove(key).commit()
    }

    private fun loadOrCreateKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .apply { init(spec) }
                .generateKey()
        }
    }.getOrNull()

    /** The Keystore picks a fresh random IV for each value; it is kept in front of the ciphertext. */
    private fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun open(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pear.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val ENCRYPTED_PREFS = "pear.secrets"
        const val PLAIN_PREFS = "pear.secrets.plain"
    }
}
