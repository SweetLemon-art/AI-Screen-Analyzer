package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.localai.Accelerator
import com.example.localai.LocalAiEvent
import com.example.localai.LocalAiProvider
import com.example.localai.LocalModel
import com.example.localai.LocalModelCatalog
import com.example.localai.LocalModelConfiguration
import com.example.localai.LocalModelRuntime
import com.example.localai.ModelCapabilities
import com.example.localai.ModelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalAiScreenProviderTest {
    @Test
    fun analyzePropagatesCancellationAndCancelsNativeRuntime() = runBlocking {
        val model = visionModel()
        val runtime = CancellationRuntime()
        val delegate = LocalAiProvider(
            modelCatalog = object : LocalModelCatalog {
                override suspend fun listModels(): List<LocalModel> = listOf(model)
            },
            runtime = runtime
        )
        check(delegate.selectModel(model.id).isSuccess)
        // selectModel() intentionally calls cancel() to establish a clean runtime boundary.
        // Reset the test observation so this test measures cancellation caused by analyze().
        runtime.cancelCount = 0

        val provider = LocalAiScreenProvider(delegate, generationTimeoutMs = 5_000L)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            provider.analyze(bitmap, AnalysisContext.DEFAULT)
            fail("CancellationException should propagate")
        } catch (_: CancellationException) {
            // Expected: cancellation is control flow and must not become AnalysisResult failure.
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        assertEquals(1, runtime.cancelCount)
    }

    @Test
    fun timeoutCancelsNativeRuntimeBeforeReturningFailure() = runBlocking {
        val model = visionModel()
        val runtime = HangingRuntime()
        val delegate = LocalAiProvider(
            modelCatalog = object : LocalModelCatalog {
                override suspend fun listModels(): List<LocalModel> = listOf(model)
            },
            runtime = runtime
        )
        check(delegate.selectModel(model.id).isSuccess)
        // See the cancellation test above: isolate the analysis-time cancellation signal.
        runtime.cancelCount = 0

        val provider = LocalAiScreenProvider(delegate, generationTimeoutMs = 1L)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val result = try {
            provider.analyze(bitmap, AnalysisContext.DEFAULT)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        assertEquals(false, result.isSuccess)
        assertEquals("Local AI generation timed out", result.errorMessage)
        assertEquals(1, runtime.cancelCount)
    }

    private fun visionModel() = LocalModel(
        id = "vision-model",
        fileName = "model.litertlm",
        modelType = ModelType.LLM,
        configuration = LocalModelConfiguration(),
        capabilities = ModelCapabilities(image = true),
        accelerator = Accelerator.CPU,
        importedAtEpochMillis = 1L
    )

    private class CancellationRuntime : LocalModelRuntime {
        var cancelCount = 0

        override suspend fun load(model: LocalModel): Result<Unit> = Result.success(Unit)

        override fun generate(prompt: String): Flow<LocalAiEvent> = cancelledFlow()

        override fun generate(prompt: String, imageBytes: ByteArray): Flow<LocalAiEvent> =
            cancelledFlow()

        private fun cancelledFlow(): Flow<LocalAiEvent> = flow {
            throw CancellationException("test cancellation")
        }

        override suspend fun unload() = Unit

        override suspend fun cancel() {
            cancelCount += 1
        }
    }

    private class HangingRuntime : LocalModelRuntime {
        var cancelCount = 0

        override suspend fun load(model: LocalModel): Result<Unit> = Result.success(Unit)

        override fun generate(prompt: String): Flow<LocalAiEvent> = hangingFlow()

        override fun generate(prompt: String, imageBytes: ByteArray): Flow<LocalAiEvent> =
            hangingFlow()

        private fun hangingFlow(): Flow<LocalAiEvent> = flow {
            emit(LocalAiEvent.Started)
            awaitCancellation()
        }

        override suspend fun unload() = Unit

        override suspend fun cancel() {
            cancelCount += 1
        }
    }
}
