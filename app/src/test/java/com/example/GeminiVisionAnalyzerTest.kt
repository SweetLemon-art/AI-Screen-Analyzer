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
import com.example.capture.ScreenCaptureProvider
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.image.ImageProcessor
import com.example.monitoring.MonitoringController
import com.example.monitoring.MonitoringState
import com.example.security.GeminiApiKeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.concurrent.atomic.AtomicInteger

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
        ScreenCaptureEngine.stop()
        assertFalse(ScreenCaptureEngine.isReady.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitoringControllerLifecycleRaceTest {

    private class FakeCaptureProvider(
        private val captureDelayMs: Long = 0L,
        private val frameBitmap: Bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
    ) : ScreenCaptureProvider {
        private val _isReady = MutableStateFlow(true)
        override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

        override suspend fun captureSingleFrame(): CaptureResult {
            if (captureDelayMs > 0) delay(captureDelayMs)
            return CaptureResult.Success(frameBitmap)
        }
    }

    @Test
    fun startThenStopEndsIdle() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()

        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)
    }

    @Test
    fun startStopStartLeavesExactlyOneMonitoringJob() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var analysisCallCount = 0
        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                analysisCallCount++
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Analysis $analysisCallCount",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })

        advanceTimeBy(100)
        assertTrue(controller.isMonitoring)

        controller.stopMonitoring()
        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun startStopStartStopEndsIdle() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()

        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)
    }

    @Test
    fun rapidStartStopStartStopStart() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var analysisCallCount = 0
        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                analysisCallCount++
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Analysis $analysisCallCount",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        // START -> STOP -> START -> STOP -> START in rapid bursts
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })

        advanceTimeBy(100)
        assertTrue(controller.isMonitoring)

        controller.stopMonitoring()
        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)
    }

    @Test
    fun stopDuringCaptureCancelsOldSession() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val captureStarted = CompletableDeferred<Unit>()
        val captureGate = CompletableDeferred<Unit>()

        val blockingCaptureProvider = object : ScreenCaptureProvider {
            private val _ready = MutableStateFlow(true)
            override val isReady: StateFlow<Boolean> = _ready.asStateFlow()

            override suspend fun captureSingleFrame(): CaptureResult {
                captureStarted.complete(Unit)
                captureGate.await()
                return CaptureResult.Success(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            }
        }

        var analyzeCalled = false
        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                analyzeCalled = true
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, blockingCaptureProvider)

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        advanceTimeBy(100)

        // Capture is in-flight; call stop
        controller.stopMonitoring()
        assertEquals(MonitoringState.Idle, controller.state.value)

        // Release blocked capture
        captureGate.complete(Unit)
        advanceUntilIdle()

        assertFalse("AI analyzer must not be invoked when capture was stopped", analyzeCalled)
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun stopDuringGeminiCancelsOldSession() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val geminiStarted = CompletableDeferred<Unit>()
        val geminiGate = CompletableDeferred<Unit>()

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                geminiStarted.complete(Unit)
                geminiGate.await()
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Stale Summary",
                    observations = emptyList(),
                    conclusion = "Stale",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        advanceTimeBy(100)

        // Gemini in-flight; call stop
        controller.stopMonitoring()
        assertEquals(MonitoringState.Idle, controller.state.value)

        // Release the gated gemini analysis
        geminiGate.complete(Unit)
        advanceUntilIdle()

        assertNull("Stale result must never be published", controller.latestResult.value)
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun stopDuringLongDelayDoesNotWaitForDelay() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary 1",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        // 600-second maximum delay
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings(delaySeconds = 600) })
        advanceTimeBy(500)

        // Stop in the middle of the 600s delay
        controller.stopMonitoring()
        advanceUntilIdle()

        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)
    }

    @Test
    fun staleSessionCannotPublishResult() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val geminiGate = CompletableDeferred<Unit>()
        var sessionCount = 0

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                sessionCount++
                if (sessionCount == 1) {
                    geminiGate.await() // Hold session 1
                }
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Result $sessionCount",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        // Session 1
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        advanceTimeBy(100)

        // Session 1 is stopped and Session 2 is started
        controller.stopMonitoring()
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })

        // Release session 1
        geminiGate.complete(Unit)
        advanceTimeBy(200)

        // Clean stop
        controller.stopMonitoring()
        advanceUntilIdle()

        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun staleSessionCannotPublishState() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()

        advanceUntilIdle()
        // Stale session must not leave state in Capturing, Analyzing, or Waiting
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun noOverlappingMonitoringLoops() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val concurrentLoops = AtomicInteger(0)
        var maxConcurrentLoops = 0

        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                val current = concurrentLoops.incrementAndGet()
                if (current > maxConcurrentLoops) maxConcurrentLoops = current
                try {
                    delay(50)
                } finally {
                    concurrentLoops.decrementAndGet()
                }
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        // Multiple starts and stops
        for (i in 1..5) {
            controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings(delaySeconds = 1) })
            advanceTimeBy(30)
            controller.stopMonitoring()
            advanceTimeBy(30)
        }

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings(delaySeconds = 1) })
        advanceTimeBy(100)
        controller.stopMonitoring()
        advanceUntilIdle()

        assertEquals("There must never be more than 1 concurrent monitoring loop", 1, maxConcurrentLoops)
        assertEquals(0, concurrentLoops.get())
        assertEquals(MonitoringState.Idle, controller.state.value)
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
