package com.example.localai

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAiConcurrencyTest {
    private val model = LocalModel(
        id = "vision-model",
        fileName = "model.litertlm",
        modelType = ModelType.LLM,
        configuration = LocalModelConfiguration(),
        capabilities = ModelCapabilities(image = true),
        accelerator = Accelerator.CPU,
        importedAtEpochMillis = 1L
    )

    @Test
    fun concurrentGenerationsAreSerialized() = runBlocking {
        val runtime = BlockingRuntime()
        val provider = LocalAiProvider(FakeCatalog(model), runtime)
        provider.selectModel(model.id)

        val first = async { provider.generate("first", byteArrayOf(1)).collect {} }
        val second = async { provider.generate("second", byteArrayOf(2)).collect {} }

        first.await()
        second.await()

        assertEquals(1, runtime.maximumConcurrent.get())
        assertEquals(2, runtime.completed.get())
    }

    @Test
    fun modelSelectionWaitsForActiveGeneration() = runBlocking {
        val runtime = BlockingRuntime()
        val provider = LocalAiProvider(FakeCatalog(model), runtime)
        provider.selectModel(model.id)

        val generation = async { provider.generate("first", byteArrayOf(1)).collect {} }
        while (runtime.active.get() == 0) delay(1)

        val replacement = async { provider.selectModel(model.id) }
        delay(10)
        assertEquals(0, runtime.loadCalls.get())

        runtime.release()
        generation.await()
        replacement.await()

        assertEquals(1, runtime.loadCalls.get())
    }

    private class FakeCatalog(private val model: LocalModel) : LocalModelCatalog {
        override suspend fun listModels(): List<LocalModel> = listOf(model)
    }

    private class BlockingRuntime : LocalModelRuntime {
        val active = AtomicInteger(0)
        val maximumConcurrent = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val loadCalls = AtomicInteger(0)
        private var release = false

        override suspend fun load(model: LocalModel): Result<Unit> {
            loadCalls.incrementAndGet()
            return Result.success(Unit)
        }

        override fun generate(prompt: String, imageBytes: ByteArray): Flow<LocalAiEvent> = flow {
            val current = active.incrementAndGet()
            maximumConcurrent.updateAndGet { previous -> maxOf(previous, current) }
            emit(LocalAiEvent.Started)
            while (!release) delay(1)
            emit(LocalAiEvent.Token(prompt))
            emit(LocalAiEvent.Completed)
            completed.incrementAndGet()
            active.decrementAndGet()
        }

        override fun generate(prompt: String): Flow<LocalAiEvent> = generate(prompt, byteArrayOf(1))

        override suspend fun unload() = Unit

        override suspend fun cancel() {
            release = true
        }

        fun release() {
            release = true
        }
    }
}
