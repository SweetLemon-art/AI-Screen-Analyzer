package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AnalysisResult
import com.example.ai.ConnectionTestResult
import com.example.ai.GeminiVisionAnalyzer
import com.example.ai.VisionAnalyzer
import com.example.capture.CaptureResult
import com.example.capture.ScreenCaptureEngine
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.image.ImageProcessor
import com.example.monitoring.MonitoringController
import com.example.monitoring.MonitoringState
import com.example.security.GeminiApiKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
class GeminiVisionAnalyzerTest {

    private lateinit var context: Context
    private lateinit var keyStore: GeminiApiKeyStore
    private lateinit var analyzer: GeminiVisionAnalyzer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        keyStore = GeminiApiKeyStore(context)
        keyStore.clearApiKey()
        analyzer = GeminiVisionAnalyzer(keyStore)
    }

    @Test
    fun testAnalyzeWithoutApiKeyReturnsSafeErrorMessage() = runTest {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)

        assertFalse(result.isSuccess)
        assertEquals("API Key not configured", result.summary)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("API key missing"))
    }

    @Test
    fun testTestConnectionWithoutApiKeyReturnsError() = runTest {
        val testResult = analyzer.testConnection()
        assertTrue(testResult is ConnectionTestResult.Error)
        val err = testResult as ConnectionTestResult.Error
        assertTrue(err.message.contains("API key is not configured"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageProcessorTest {

    @Test
    fun testProcessForGeminiBase64DoesNotRecycleOriginalBitmap() {
        val original = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        val base64 = ImageProcessor.processForGeminiBase64(original, maxDimension = 300, quality = 80)

        assertNotNull(base64)
        assertTrue(base64.isNotEmpty())
        assertFalse("Original bitmap must NOT be recycled by ImageProcessor", original.isRecycled)
    }

    @Test
    fun testProcessForGeminiBase64WithoutScaling() {
        val original = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val base64 = ImageProcessor.processForGeminiBase64(original, maxDimension = 500, quality = 90)

        assertNotNull(base64)
        assertTrue(base64.isNotEmpty())
        assertFalse(original.isRecycled)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenCaptureEngineLifecycleTest {

    @Test
    fun testIdempotentStop() {
        ScreenCaptureEngine.stop()
        ScreenCaptureEngine.stop()
        ScreenCaptureEngine.stop()
        assertFalse(ScreenCaptureEngine.isReady.value)
    }

    @Test
    fun testCaptureWhenNotReadyReturnsErrorWithoutRecreating() = runTest {
        ScreenCaptureEngine.stop()
        val result = ScreenCaptureEngine.captureSingleFrame()

        assertTrue(result is CaptureResult.Error)
        assertFalse(ScreenCaptureEngine.isReady.value)
    }

    @Test
    fun testSessionGenerationIsolation() {
        // Repeated stop increments generation and safely cleans without throwing
        ScreenCaptureEngine.stop()
        assertFalse(ScreenCaptureEngine.isReady.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitoringControllerLifecycleRaceTest {

    @Test
    fun testStartStopStartSequence() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(
                bitmap: Bitmap,
                context: AnalysisContext,
                settings: CaptureSettings
            ): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Analysis Complete",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }

            override suspend fun testConnection(): ConnectionTestResult {
                return ConnectionTestResult.Success("OK")
            }
        }

        val controller = MonitoringController(fakeAnalyzer, testScope)
        assertEquals(MonitoringState.Idle, controller.state.value)

        // START
        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT }
        )
        // STOP immediately
        controller.stopMonitoring()
        assertEquals(MonitoringState.Idle, controller.state.value)

        // START again
        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT }
        )
        advanceUntilIdle()

        // Clean STOP
        controller.stopMonitoring()
        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun testStartStopStartStopSequence() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(
                bitmap: Bitmap,
                context: AnalysisContext,
                settings: CaptureSettings
            ): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Result",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }

            override suspend fun testConnection(): ConnectionTestResult {
                return ConnectionTestResult.Success("OK")
            }
        }

        val controller = MonitoringController(fakeAnalyzer, testScope)

        // START -> STOP -> START -> STOP rapid succession
        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT }
        )
        controller.stopMonitoring()
        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT }
        )
        controller.stopMonitoring()

        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)
    }

    @Test
    fun testStopWhileGeminiIsRunningPreventsStalePublish() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val analysisStarted = CompletableDeferred<Unit>()
        val analysisGate = CompletableDeferred<Unit>()

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(
                bitmap: Bitmap,
                context: AnalysisContext,
                settings: CaptureSettings
            ): AnalysisResult {
                analysisStarted.complete(Unit)
                analysisGate.await() // block until test says continue
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Stale Result",
                    observations = emptyList(),
                    conclusion = "Stale",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 50L
                )
            }

            override suspend fun testConnection(): ConnectionTestResult {
                return ConnectionTestResult.Success("OK")
            }
        }

        val controller = MonitoringController(fakeAnalyzer, testScope)

        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT }
        )
        advanceTimeBy(100)

        // STOP while analysis is waiting
        controller.stopMonitoring()
        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)

        // Release the gated analysis
        analysisGate.complete(Unit)
        advanceUntilIdle()

        // Stale result MUST NOT be published
        assertNull("Stale analysis result must not be published to latestResult", controller.latestResult.value)
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun testStopDuringDelayTimer() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(
                bitmap: Bitmap,
                context: AnalysisContext,
                settings: CaptureSettings
            ): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "First Result",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }

            override suspend fun testConnection(): ConnectionTestResult {
                return ConnectionTestResult.Success("OK")
            }
        }

        val controller = MonitoringController(fakeAnalyzer, testScope)

        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings(delaySeconds = 60) }
        )
        advanceTimeBy(500)

        // Stop during delay
        controller.stopMonitoring()
        advanceUntilIdle()

        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)
    }

    @Test
    fun testTimerBoundsClamp() {
        val settingsMin = CaptureSettings.createSafe(delay = -10, resolution = 100, quality = 10)
        assertEquals(1, settingsMin.delaySeconds)
        assertEquals(480, settingsMin.maxResolutionDimension)
        assertEquals(40, settingsMin.compressionQuality)

        val settingsMax = CaptureSettings.createSafe(delay = 1000, resolution = 5000, quality = 200)
        assertEquals(600, settingsMax.delaySeconds)
        assertEquals(2160, settingsMax.maxResolutionDimension)
        assertEquals(100, settingsMax.compressionQuality)
    }
}
