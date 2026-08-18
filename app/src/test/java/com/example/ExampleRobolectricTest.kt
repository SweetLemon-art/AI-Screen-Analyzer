package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AnalysisResult
import com.example.ai.GeminiVisionAnalyzer
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.data.SettingsRepository
import com.example.security.GeminiApiKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiApiKeyStoreTest {

    private lateinit var context: Context
    private lateinit var keyStore: GeminiApiKeyStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        keyStore = GeminiApiKeyStore(context)
        keyStore.clearApiKey()
    }

    @Test
    fun testSaveAndRetrieveApiKey() {
        assertFalse(keyStore.hasApiKey())
        assertNull(keyStore.getApiKey())

        val testKey = "AIzaSyTestKey1234567890abcdef"
        keyStore.saveApiKey(testKey)

        assertTrue(keyStore.hasApiKey())
        assertEquals(testKey, keyStore.getApiKey())
    }

    @Test
    fun testMaskedApiKey() {
        val testKey = "AIzaSyTestKey1234567890abcdef"
        keyStore.saveApiKey(testKey)

        val masked = keyStore.getMaskedApiKey()
        assertTrue(masked.startsWith("••••••••••••"))
        assertTrue(masked.endsWith("cdef"))
    }

    @Test
    fun testClearApiKey() {
        keyStore.saveApiKey("AIzaSyTestKey1234567890")
        assertTrue(keyStore.hasApiKey())

        keyStore.clearApiKey()
        assertFalse(keyStore.hasApiKey())
        assertNull(keyStore.getApiKey())
        assertEquals("", keyStore.getMaskedApiKey())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
    }

    @Test
    fun testSaveAndLoadSettings() {
        val customSettings = CaptureSettings(
            delaySeconds = 15,
            maxResolutionDimension = 720,
            compressionQuality = 85
        )
        repository.saveSettings(customSettings)

        val loaded = repository.loadSettings()
        assertEquals(15, loaded.delaySeconds)
        assertEquals(720, loaded.maxResolutionDimension)
        assertEquals(85, loaded.compressionQuality)
    }

    @Test
    fun testDelayClamping() {
        val settingsMin = CaptureSettings(delaySeconds = 1, maxResolutionDimension = 1080, compressionQuality = 80)
        val settingsMax = CaptureSettings(delaySeconds = 600, maxResolutionDimension = 1080, compressionQuality = 80)

        repository.saveSettings(settingsMin)
        assertEquals(1, repository.loadSettings().delaySeconds)

        repository.saveSettings(settingsMax)
        assertEquals(600, repository.loadSettings().delaySeconds)
    }

    @Test
    fun testSaveAndLoadContexts() {
        val customContext = AnalysisContext(
            id = "custom_test_1",
            name = "Test Context",
            instructions = "Focus on UI elements.",
            language = "Spanish",
            isPreset = false
        )
        repository.saveContexts(listOf(customContext))

        val loaded = repository.loadContexts()
        assertEquals(1, loaded.size)
        assertEquals("Test Context", loaded[0].name)
        assertEquals("Spanish", loaded[0].language)
    }

    @Test
    fun testSelectedContextId() {
        repository.saveSelectedContextId("chart_analysis")
        assertEquals("chart_analysis", repository.loadSelectedContextId())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureSettingsTest {

    @Test
    fun testDefaults() {
        val defaultSettings = CaptureSettings.DEFAULT
        assertEquals(5, defaultSettings.delaySeconds)
        assertEquals(1080, defaultSettings.maxResolutionDimension)
        assertEquals(80, defaultSettings.compressionQuality)
    }

    @Test
    fun testDelayPresets() {
        assertTrue(CaptureSettings.DELAY_PRESETS.contains(1))
        assertTrue(CaptureSettings.DELAY_PRESETS.contains(5))
        assertTrue(CaptureSettings.DELAY_PRESETS.contains(60))
        assertTrue(CaptureSettings.DELAY_PRESETS.contains(600))
    }
}
