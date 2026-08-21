package com.example.localai

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        runtime.loadCalls.set(0)

        val first = async { provider.generate("first", byteArrayOf(1)).collect {} }
        runtime.firstStarted.await()

        val second = async { provider.generate("second", byteArrayOf(2)).collect {} }
        assertFalse(runtime.secondStarted.isCompleted)
        assertEquals(0, runtime.completed.get())

        runtime.release()
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
        runtime.loadCalls.set(0)

        val generation = async { provider.generate("first", byteArrayOf(1)).collect {} }
        runtime.firstStarted.await()

        val replacement = async { provider.selectModel(model.id) }
        assertEquals(0, runtime.loadCalls.get())
        assertFalse(replacement.isCompleted)

        runtime.release()
        generation.await()
        replacement.await()

        assertEquals(1, runtime.loadCalls.get())
    }

    private class FakeCatalog(private val model: LocalModel) : LocalModelCatalog {
        override suspend fun listModels(): List<LocalModel> = listOf(model)
    }

    private class BlockingRuntime : LocalModelRuntime {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        val maximumConcurrent = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val loadCalls = AtomicInteger(0)
        private val releaseGate = CompletableDeferred<Unit>()
        private var generationCount = 0

        override suspend fun load(model: LocalModel): Result<Unit> {
            loadCalls.incrementAndGet()
            return Result.success(Unit)
        }

        override fun generate(prompt: String, imageBytes: ByteArray) = kotlinx.coroutines.flow.flow {
            val invocation = ++generationCount
            if (invocation == 1) firstStarted.complete(Unit) else secondStarted.complete(Unit)
            val current = active.incrementAndGet()
            maximumConcurrent.updateAndGet { previous -> maxOf(previous, current) }
            try {
                emit(LocalAiEvent.Started)
                releaseGate.await()
                emit(LocalAiEvent.Token(prompt))
                emit(LocalAiEvent.Completed)
                completed.incrementAndGet()
            } finally {
                active.decrementAndGet()
            }
        }

        override fun generate(prompt: String): kotlinx.coroutines.flow.Flow<LocalAiEvent> =
            generate(prompt, byteArrayOf(1))

        override suspend fun unload() = Unit

        override suspend fun cancel() {
            releaseGate.complete(Unit)
        }

        fun release() {
            releaseGate.complete(Unit)
        }
    }
}
