package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.data.SettingsRepository
import com.example.security.GeminiApiKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val settingsMin = CaptureSettings.createSafe(delay = -5, resolution = 100, quality = 20)
        val settingsMax = CaptureSettings.createSafe(delay = 1000, resolution = 4000, quality = 150)

        repository.saveSettings(settingsMin)
        val loadedMin = repository.loadSettings()
        assertEquals(1, loadedMin.delaySeconds)
        assertEquals(480, loadedMin.maxResolutionDimension)
        assertEquals(40, loadedMin.compressionQuality)

        repository.saveSettings(settingsMax)
        val loadedMax = repository.loadSettings()
        assertEquals(600, loadedMax.delaySeconds)
        assertEquals(2160, loadedMax.maxResolutionDimension)
        assertEquals(100, loadedMax.compressionQuality)
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

    @Test
    fun testDiscoveredModelsPersistence() {
        val models = listOf(
            com.example.ai.GeminiModel(
                name = "models/test-vision-pro",
                displayName = "Test Vision Pro",
                description = "Multimodal test model",
                supportedGenerationMethods = listOf("generateContent"),
                inputTokenLimit = 1048576,
                outputTokenLimit = 8192,
                imageInputCapability = com.example.ai.ImageInputCapability.SUPPORTED
            )
        )
        repository.saveDiscoveredModels(models)
        val loaded = repository.loadDiscoveredModels()
        assertEquals(1, loaded.size)
        assertEquals("test-vision-pro", loaded[0].modelId)
        assertEquals("Test Vision Pro", loaded[0].displayName)
        assertEquals(1048576, loaded[0].inputTokenLimit)
        assertEquals(com.example.ai.ImageInputCapability.SUPPORTED, loaded[0].imageInputCapability)

        repository.saveSelectedModel("models/test-vision-pro")
        assertEquals("test-vision-pro", repository.loadSelectedModel())

        repository.clearSelectedModel()
        assertEquals("", repository.loadSelectedModel())
    }

    @Test
    fun testSelectedModelSavedNormalized() {
        repository.saveSelectedModel("  models/my-custom-model  ")
        assertEquals("my-custom-model", repository.loadSelectedModel())

        repository.saveSelectedModel("my-custom-model-2")
        assertEquals("my-custom-model-2", repository.loadSelectedModel())

        repository.saveSelectedModel("")
        assertEquals("", repository.loadSelectedModel())
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
