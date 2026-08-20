package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Secure storage for the user-provided Gemini API key.
 *
 * Production Android always uses AndroidKeyStore AES-256 GCM. The JVM fallback is
 * enabled only under Robolectric so local JVM tests can exercise the storage class
 * without weakening production credential storage.
 * The raw API key is never stored in plaintext.
 */
class GeminiApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val useJvmFallback = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
    private var jvmSecretKey: SecretKey? = null
    private var keyStoreAvailable = false

    init {
        initKeyStore()
    }

    @Synchronized
    private fun initKeyStore() {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    KEYSTORE_PROVIDER
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()

                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
            keyStoreAvailable = true
        } catch (e: Exception) {
            if (useJvmFallback) {
                initJvmFallbackKey()
            } else {
                // Production must fail closed: never replace AndroidKeyStore with a key
                // stored beside the ciphertext in SharedPreferences.
                keyStoreAvailable = false
            }
        }
    }

    private fun initJvmFallbackKey() {
        check(useJvmFallback) { "JVM fallback is restricted to Robolectric tests." }

        val storedKeyBase64 = prefs.getString(PREF_KEY_JVM_FALLBACK_KEY, null)
        if (storedKeyBase64 != null) {
            val keyBytes = Base64.decode(storedKeyBase64, Base64.NO_WRAP)
            require(keyBytes.size == JVM_AES_KEY_SIZE_BYTES) {
                "Invalid Robolectric fallback key length."
            }
            jvmSecretKey = SecretKeySpec(keyBytes, "AES")
        } else {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(JVM_AES_KEY_SIZE_BITS)
            val generatedKey = keyGen.generateKey()
            jvmSecretKey = generatedKey
            prefs.edit()
                .putString(
                    PREF_KEY_JVM_FALLBACK_KEY,
                    Base64.encodeToString(generatedKey.encoded, Base64.NO_WRAP)
                )
                .apply()
        }
        keyStoreAvailable = true
    }

    private fun getSecretKey(): SecretKey {
        if (!keyStoreAvailable) {
            throw IllegalStateException("AndroidKeyStore is unavailable; secure API key storage is disabled.")
        }

        if (useJvmFallback) {
            return jvmSecretKey
                ?: throw IllegalStateException("Robolectric fallback secret key not initialized")
        }

        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                ?: throw IllegalStateException("KeyStore secret key entry not found")
            entry.secretKey
        } catch (e: Exception) {
            keyStoreAvailable = false
            throw IllegalStateException(
                "AndroidKeyStore key could not be loaded; secure API key storage is disabled.",
                e
            )
        }
    }

    @Synchronized
    fun saveApiKey(apiKey: String): Boolean {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clearApiKey()
            return true
        }

        return try {
            val secretKey = getSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

            val encodedIv = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encodedCiphertext = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

            prefs.edit()
                .putString(PREF_KEY_IV, encodedIv)
                .putString(PREF_KEY_DATA, encodedCiphertext)
                .apply()

            // If an older production build left a JVM fallback key behind, remove it only
            // after a successful AndroidKeyStore write. Robolectric must retain its test key
            // because it is needed to decrypt persisted test data across store instances.
            if (!useJvmFallback) {
                prefs.edit().remove(PREF_KEY_JVM_FALLBACK_KEY).apply()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun getApiKey(): String? {
        val encodedIv = prefs.getString(PREF_KEY_IV, null) ?: return null
        val encodedCiphertext = prefs.getString(PREF_KEY_DATA, null) ?: return null

        return try {
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            val ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP)

            val secretKey = getSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun clearApiKey() {
        prefs.edit()
            .remove(PREF_KEY_IV)
            .remove(PREF_KEY_DATA)
            .remove(PREF_KEY_JVM_FALLBACK_KEY)
            .apply()
    }

    fun hasApiKey(): Boolean {
        val key = getApiKey()
        return !key.isNullOrBlank()
    }

    fun getMaskedApiKey(): String {
        val key = getApiKey() ?: return ""
        if (key.length <= 8) return "••••••••"
        val last4 = key.takeLast(4)
        return "••••••••••••$last4"
    }

    companion object {
        private const val PREFS_NAME = "ai_screen_analyzer_sec_prefs"
        private const val KEY_ALIAS = "gemini_api_key_aes"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PREF_KEY_IV = "enc_iv"
        private const val PREF_KEY_DATA = "enc_data"
        private const val PREF_KEY_JVM_FALLBACK_KEY = "enc_jvm_fallback_k"
        private const val JVM_AES_KEY_SIZE_BITS = 256
        private const val JVM_AES_KEY_SIZE_BYTES = JVM_AES_KEY_SIZE_BITS / 8
    }
}
