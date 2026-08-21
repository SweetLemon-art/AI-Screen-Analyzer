package com.example.monitoring

import android.graphics.Bitmap
import com.example.ai.AnalysisResult
import com.example.ai.ConnectionTestResult
import com.example.ai.GeminiModel
import com.example.ai.RateLimitState
import com.example.ai.VisionAnalyzer
import com.example.capture.CaptureResult
import com.example.capture.ScreenCaptureProvider
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringControllerPhase17RegressionTest {
    @Test
    fun startStart_leavesExactlyOneActiveMonitoringSession() = runTest {
        val provider = BlockingCaptureProvider()
        val controller = MonitoringController(
            FakeVisionAnalyzer(),
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            provider
        )

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT.copy(delaySeconds = 1) })
        runCurrent()
        assertEquals(MonitoringState.Capturing, controller.state.value)

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT.copy(delaySeconds = 1) })
        runCurrent()

        assertEquals(2, provider.captureCalls)
        assertEquals(MonitoringState.Capturing, controller.state.value)
    }

    @Test
    fun startStopStart_replacesOldSession() = runTest {
        val provider = BlockingCaptureProvider()
        val controller = MonitoringController(
            FakeVisionAnalyzer(),
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            provider
        )

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT.copy(delaySeconds = 1) })
        runCurrent()
        controller.stopMonitoring()
        runCurrent()
        assertEquals(MonitoringState.Idle, controller.state.value)

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT.copy(delaySeconds = 1) })
        runCurrent()

        assertEquals(2, provider.captureCalls)
        assertEquals(MonitoringState.Capturing, controller.state.value)
    }

    @Test
    fun resetDuringCapture_invalidatesSessionAndClearsStateImmediately() = runTest {
        val provider = BlockingCaptureProvider()
        val controller = MonitoringController(
            FakeVisionAnalyzer(),
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            provider
        )

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT.copy(delaySeconds = 1) })
        runCurrent()
        controller.resetState()

        assertEquals(MonitoringState.Idle, controller.state.value)
        assertEquals(null, controller.latestBitmap.value)
        assertEquals(null, controller.latestResult.value)
        assertEquals(0, controller.analysisCount.value)
        assertEquals(null, controller.lastCaptureTimestamp.value)
    }

    private class BlockingCaptureProvider : ScreenCaptureProvider {
        override val isReady = MutableStateFlow(true)
        var captureCalls = 0

        override suspend fun captureSingleFrame(): CaptureResult {
            captureCalls++
            return suspendCancellableCoroutine { }
        }
    }

    private class FakeVisionAnalyzer : VisionAnalyzer {
        override val rateLimitState: StateFlow<RateLimitState> = MutableStateFlow(RateLimitState.NORMAL)

        override suspend fun analyze(
            bitmap: Bitmap,
            context: AnalysisContext,
            settings: CaptureSettings
        ): AnalysisResult = error("analysis should not be reached")

        override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Error("unused")
        override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
    }
}
