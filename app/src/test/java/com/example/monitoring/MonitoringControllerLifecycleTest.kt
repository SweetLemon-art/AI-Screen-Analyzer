package com.example.monitoring

import android.graphics.Bitmap
import com.example.ai.AnalysisResult
import com.example.ai.ConnectionTestResult
import com.example.ai.GeminiModel
import com.example.ai.RateLimitState
import com.example.ai.VisionAnalyzer
import com.example.capture.CaptureResult
import com.example.capture.ScreenCaptureLifecycleProvider
import com.example.capture.ScreenCaptureProvider
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.image.ImageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class MonitoringControllerLifecycleTest {

    @Test
    fun externalCaptureSessionStop_stopsActiveMonitoring() = runTest {
        val provider = LifecycleAwareCaptureProvider()
        val controller = createController(provider, this)

        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT.copy(delaySeconds = 1) }
        )
        runCurrent()
        assertEquals(MonitoringState.Capturing, controller.state.value)

        provider.emitSessionStopped()
        runCurrent()

        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun resetState_clearsStateOnlyAfterActiveJobStops() = runTest {
        val provider = LifecycleAwareCaptureProvider()
        val controller = createController(provider, this)

        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT.copy(delaySeconds = 1) }
        )
        runCurrent()
        assertEquals(MonitoringState.Capturing, controller.state.value)

        controller.resetState()
        runCurrent()

        assertEquals(MonitoringState.Idle, controller.state.value)
        assertEquals(null, controller.latestResult.value)
        assertEquals(0, controller.analysisCount.value)
        assertEquals(null, controller.lastCaptureTimestamp.value)
    }

    @Test
    fun burstLifecycleCommands_convergeToFinalStartState() = runTest {
        val provider = LifecycleAwareCaptureProvider()
        val controller = createController(provider, this)

        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT.copy(delaySeconds = 1) }
        )
        controller.stopMonitoring()
        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT.copy(delaySeconds = 1) }
        )

        runCurrent()
        assertEquals(MonitoringState.Capturing, controller.state.value)
    }

    @Test
    fun resetThenStart_convergesToNewMonitoringSession() = runTest {
        val provider = LifecycleAwareCaptureProvider()
        val controller = createController(provider, this)

        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT.copy(delaySeconds = 1) }
        )
        runCurrent()
        assertEquals(MonitoringState.Capturing, controller.state.value)

        controller.resetState()
        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT.copy(delaySeconds = 1) }
        )

        runCurrent()
        assertEquals(MonitoringState.Capturing, controller.state.value)
        assertEquals(0, controller.analysisCount.value)
    }

    @Test
    fun publishedPreview_remainsValidAfterAnalysisCompletes() = runTest {
        val provider = SingleFrameCaptureProvider()
        val controller = createController(provider, this, SuccessfulVisionAnalyzer())

        controller.startMonitoring(
            contextProvider = { AnalysisContext.DEFAULT },
            settingsProvider = { CaptureSettings.DEFAULT.copy(delaySeconds = 1) }
        )
        runCurrent()

        val publishedPreview = controller.latestBitmap.value
        assertNotNull(publishedPreview)
        assertFalse("published preview must remain usable after analysis", publishedPreview!!.isRecycled)
        assertEquals(1, controller.analysisCount.value)

        controller.stopMonitoring()
        runCurrent()
    }

    @Test
    fun previewContract_returnsIndependentBitmapWhenNoDownscaleIsNeeded() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val preview = ImageProcessor.createPreviewBitmap(source, maxDimension = 720)
            assertNotSame(source, preview)
            assertFalse(preview.isRecycled)
            assertFalse(source.isRecycled)
            preview.recycle()
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun createController(
        provider: ScreenCaptureProvider,
        testScope: TestScope,
        analyzer: VisionAnalyzer = FakeVisionAnalyzer()
    ): MonitoringController {
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        return MonitoringController(analyzer, CoroutineScope(dispatcher), provider)
    }

    private class LifecycleAwareCaptureProvider : ScreenCaptureProvider, ScreenCaptureLifecycleProvider {
        override val isReady = MutableStateFlow(true)
        private var listener: (() -> Unit)? = null

        override fun setOnSessionStoppedListener(listener: (() -> Unit)?) {
            this.listener = listener
        }

        override suspend fun captureSingleFrame(): CaptureResult =
            suspendCancellableCoroutine { }

        fun emitSessionStopped() {
            listener?.invoke()
        }
    }

    private class SingleFrameCaptureProvider : ScreenCaptureProvider {
        override val isReady = MutableStateFlow(true)
        private val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        private var delivered = false

        override suspend fun captureSingleFrame(): CaptureResult {
            if (delivered) return suspendCancellableCoroutine { }
            delivered = true
            return CaptureResult.Success(bitmap)
        }
    }

    private class FakeVisionAnalyzer : VisionAnalyzer {
        override val rateLimitState: StateFlow<RateLimitState> =
            MutableStateFlow(RateLimitState.NORMAL)

        override suspend fun analyze(
            bitmap: Bitmap,
            context: AnalysisContext,
            settings: CaptureSettings
        ): AnalysisResult {
            throw AssertionError("analyze() should not be reached by lifecycle tests")
        }

        override suspend fun testConnection(): ConnectionTestResult =
            ConnectionTestResult.Error("not used")

        override suspend fun discoverModels(): Result<List<GeminiModel>> =
            Result.success(emptyList())
    }

    private class SuccessfulVisionAnalyzer : VisionAnalyzer {
        override val rateLimitState: StateFlow<RateLimitState> =
            MutableStateFlow(RateLimitState.NORMAL)

        override suspend fun analyze(
            bitmap: Bitmap,
            context: AnalysisContext,
            settings: CaptureSettings
        ): AnalysisResult = AnalysisResult(
            contextName = context.name,
            summary = "ok",
            observations = emptyList(),
            conclusion = "ok",
            isSuccess = true
        )

        override suspend fun testConnection(): ConnectionTestResult =
            ConnectionTestResult.Error("not used")

        override suspend fun discoverModels(): Result<List<GeminiModel>> =
            Result.success(emptyList())
    }
}
