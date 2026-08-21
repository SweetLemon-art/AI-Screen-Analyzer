package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeminiAiProviderRetryPolicyTest {
    private val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @Test
    fun transientServerFailureIsReturnedAfterSingleDelegateInvocation() = runBlocking {
        val delegate = CountingVisionAnalyzer(
            AnalysisResult(
                contextName = AnalysisContext.DEFAULT.name,
                summary = "Analysis request failed (503)",
                isSuccess = false,
                errorMessage = "Gemini service temporarily unavailable."
            )
        )
        val provider = GeminiAiProvider(delegate)

        val result = provider.analyze(
            bitmap = bitmap,
            context = AnalysisContext.DEFAULT,
            settings = CaptureSettings.DEFAULT
        )

        assertFalse(result.isSuccess)
        assertEquals(1, delegate.invocationCount)
    }

    @Test
    fun successfulDelegateResultIsReturnedWithoutAdditionalInvocation() = runBlocking {
        val delegate = CountingVisionAnalyzer(
            AnalysisResult(
                contextName = AnalysisContext.DEFAULT.name,
                summary = "ok",
                isSuccess = true
            )
        )
        val provider = GeminiAiProvider(delegate)

        val result = provider.analyze(
            bitmap = bitmap,
            context = AnalysisContext.DEFAULT,
            settings = CaptureSettings.DEFAULT,
            userPrompt = "What is visible?"
        )

        assertTrue(result.isSuccess)
        assertEquals("ok", result.summary)
        assertEquals(1, delegate.invocationCount)
        assertEquals("Base instructions\n\nUser question: What is visible?", delegate.lastContext.instructions)
    }

    private class CountingVisionAnalyzer(
        private val response: AnalysisResult
    ) : VisionAnalyzer {
        override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
        var invocationCount: Int = 0
            private set
        lateinit var lastContext: AnalysisContext
            private set

        override suspend fun analyze(
            bitmap: Bitmap,
            context: AnalysisContext,
            settings: CaptureSettings
        ): AnalysisResult {
            invocationCount += 1
            lastContext = context
            return response
        }

        override suspend fun testConnection(): ConnectionTestResult =
            ConnectionTestResult.Error("not used")

        override suspend fun discoverModels(): Result<List<GeminiModel>> =
            Result.success(emptyList())
    }
}
