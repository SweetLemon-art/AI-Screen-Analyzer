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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

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

    private fun createController(
        provider: LifecycleAwareCaptureProvider,
        testScope: TestScope
    ): MonitoringController {
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        return MonitoringController(FakeVisionAnalyzer(), CoroutineScope(dispatcher), provider)
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
}
