package com.example.localai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalAiFatalErrorRegressionTest {
    @Test
    fun runtimeError_propagates_instead_of_becoming_failed_event() = runTest {
        val model = LocalModel(
            id = "model-1",
            fileName = "model.litertlm",
            modelType = ModelType.LLM,
            configuration = LocalModelConfiguration(),
            capabilities = ModelCapabilities(),
            accelerator = Accelerator.CPU,
            importedAtEpochMillis = 1L
        )
        val runtime = FatalRuntime()
        val provider = LocalAiProvider(FakeCatalog(model), runtime)
        provider.selectModel(model.id)

        assertThrows(AssertionError::class.java) {
            provider.generate("hello").toList()
        }
    }

    private class FakeCatalog(private val model: LocalModel) : LocalModelCatalog {
        override suspend fun listModels(): List<LocalModel> = listOf(model)
    }

    private class FatalRuntime : LocalModelRuntime {
        override suspend fun load(model: LocalModel): Result<Unit> = Result.success(Unit)

        override fun generate(prompt: String): Flow<LocalAiEvent> = flow {
            throw AssertionError("fatal runtime failure")
        }

        override fun generate(prompt: String, imageBytes: ByteArray): Flow<LocalAiEvent> = flow {
            throw AssertionError("fatal runtime failure")
        }

        override suspend fun unload() = Unit
        override suspend fun cancel() = Unit
    }
}
