package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.security.GeminiApiKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiApiKeyStoreSecurityTest {

    private lateinit var application: Application
    private lateinit var store: GeminiApiKeyStore

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        store = GeminiApiKeyStore(application)
        store.clearApiKey()
    }

    @Test
    fun `api key round trip works in Robolectric without plaintext preference storage`() {
        val prefs = application.getSharedPreferences(
            "ai_screen_analyzer_sec_prefs",
            android.content.Context.MODE_PRIVATE
        )

        assertTrue(store.saveApiKey("test-gemini-api-key-1234"))
        assertEquals("test-gemini-api-key-1234", store.getApiKey())
        assertTrue(store.hasApiKey())
        assertEquals("••••••••••••1234", store.getMaskedApiKey())

        assertTrue(prefs.contains("enc_iv"))
        assertTrue(prefs.contains("enc_data"))
        assertTrue(prefs.contains("enc_jvm_fallback_k"))
        assertFalse(prefs.getString("enc_data", "").contains("test-gemini-api-key-1234"))
    }

    @Test
    fun `clear removes encrypted material and fallback test key`() {
        assertTrue(store.saveApiKey("test-gemini-api-key-5678"))
        store.clearApiKey()

        assertFalse(store.hasApiKey())
        assertEquals("", store.getMaskedApiKey())

        val prefs = application.getSharedPreferences(
            "ai_screen_analyzer_sec_prefs",
            android.content.Context.MODE_PRIVATE
        )
        assertFalse(prefs.contains("enc_iv"))
        assertFalse(prefs.contains("enc_data"))
        assertFalse(prefs.contains("enc_jvm_fallback_k"))
    }
}
