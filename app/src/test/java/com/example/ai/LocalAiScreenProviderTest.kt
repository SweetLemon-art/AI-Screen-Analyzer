package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.localai.Accelerator
import com.example.localai.LocalAiProvider
import com.example.localai.LocalModel
import com.example.localai.LocalModelCatalog
import com.example.localai.LocalModelConfiguration
import com.example.localai.LocalModelRuntime
import com.example.localai.ModelCapabilities
import com.example.localai.ModelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

class LocalAiScreenProviderTest {
    @Test
    fun analyzePropagatesCancellationInsteadOfReturningFailure() = runBlocking {
        val model = LocalModel(
            id = "vision-model",
            fileName = "model.litertlm",
            modelType = ModelType.LLM,
            configuration = LocalModelConfiguration(),
            capabilities = ModelCapabilities(image = true),
            accelerator = Accelerator.CPU,
            importedAtEpochMillis = 1L
        )
        val runtime = CancellationRuntime()
        val delegate = LocalAiProvider(object : LocalModelCatalog {
            override suspend fun listModels(): List<LocalModel> = listOf(model)
        }, runtime)
        check(delegate.selectModel(model.id).isSuccess)

        val provider = LocalAiScreenProvider(delegate, generationTimeoutMs = 5_000L)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            provider.analyze(bitmap, AnalysisContext.DEFAULT)
            fail("CancellationException should propagate")
        } catch (_: CancellationException) {
            // Expected: coroutine cancellation must never become AnalysisResult failure.
        } finally {
            bitmap.recycle()
        }
    }

    private class CancellationRuntime : LocalModelRuntime {
        override suspend fun load(model: LocalModel): Result<Unit> = Result.success(Unit)

        override fun generate(prompt: String): Flow<com.example.localai.LocalAiEvent> = cancelledFlow()

        override fun generate(prompt: String, imageBytes: ByteArray): Flow<com.example.localai.LocalAiEvent> = cancelledFlow()

        private fun cancelledFlow(): Flow<com.example.localai.LocalAiEvent> = flow {
            throw CancellationException("test cancellation")
        }

        override suspend fun unload() = Unit
        override suspend fun cancel() = Unit
    }
}
