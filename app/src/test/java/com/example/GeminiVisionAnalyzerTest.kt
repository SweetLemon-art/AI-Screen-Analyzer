package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AnalysisResult
import com.example.ai.ConnectionTestResult
import com.example.ai.GeminiVisionAnalyzer
import com.example.ai.VisionAnalyzer
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.monitoring.MonitoringController
import com.example.monitoring.MonitoringState
import com.example.security.GeminiApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitoringControllerRaceTest {

    @Test
    fun testStartStopStartLifecycleNoLeak() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var analyzeCallCount = 0
        val fakeAnalyzer = object : VisionAnalyzer {
            override suspend fun analyze(
                bitmap: Bitmap,
                context: AnalysisContext,
                settings: CaptureSettings
            ): AnalysisResult {
                analyzeCallCount++
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Result $analyzeCallCount",
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

        // Stop while idle is completely safe
        controller.stopMonitoring()
        assertEquals(MonitoringState.Idle, controller.state.value)

        // Multiple stop calls are safe and idempotent
        controller.stopMonitoring()
        controller.stopMonitoring()
        assertEquals(MonitoringState.Idle, controller.state.value)
    }
}
