package com.example.localai

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiConcurrencyTest {
    private val firstModel = LocalModel(
        id = "vision-model-a",
        fileName = "model-a.litertlm",
        modelType = ModelType.LLM,
        configuration = LocalModelConfiguration(),
        capabilities = ModelCapabilities(image = true),
        accelerator = Accelerator.CPU,
        importedAtEpochMillis = 1L
    )

    private val secondModel = firstModel.copy(
        id = "vision-model-b",
        fileName = "model-b.litertlm",
        importedAtEpochMillis = 2L
    )

    @Test
    fun concurrentGenerationsAreSerialized() = runBlocking {
        val runtime = BlockingRuntime()
        val provider = LocalAiProvider(FakeCatalog(firstModel), runtime)
        provider.selectModel(firstModel.id)
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
    fun selectingAlreadyLoadedModelDoesNotReload() = runBlocking {
        val runtime = BlockingRuntime()
        val provider = LocalAiProvider(FakeCatalog(firstModel, secondModel), runtime)

        provider.selectModel(firstModel.id)
        runtime.loadCalls.set(0)

        val result = provider.selectModel(firstModel.id)

        assertTrue(result.isSuccess)
        assertEquals(0, runtime.loadCalls.get())
        assertEquals(LocalModelState.Ready(firstModel.id), provider.modelState.value)
    }

    @Test
    fun modelSelectionCancelsActiveGenerationBeforeSwitching() = runBlocking {
        val runtime = BlockingRuntime()
        val provider = LocalAiProvider(FakeCatalog(firstModel, secondModel), runtime)
        provider.selectModel(firstModel.id)
        runtime.loadCalls.set(0)

        val generation = async { provider.generate("first", byteArrayOf(1)).collect {} }
        runtime.firstStarted.await()

        val replacement = provider.selectModel(secondModel.id)
        generation.await()

        assertTrue(replacement.isSuccess)
        assertTrue(runtime.cancelled)
        assertEquals(1, runtime.loadCalls.get())
        assertEquals(secondModel.id, provider.selectedModelId())
        assertEquals(LocalModelState.Ready(secondModel.id), provider.modelState.value)
    }

    @Test
    fun failedModelSwitchClearsStaleSelection() = runBlocking {
        val runtime = BlockingRuntime(failModelId = secondModel.id)
        val provider = LocalAiProvider(FakeCatalog(firstModel, secondModel), runtime)
        provider.selectModel(firstModel.id)
        runtime.loadCalls.set(0)

        val result = provider.selectModel(secondModel.id)

        assertTrue(result.isFailure)
        assertEquals(null, provider.selectedModelId())
        assertTrue(provider.modelState.value is LocalModelState.Failed)
    }

    private class FakeCatalog(private vararg val models: LocalModel) : LocalModelCatalog {
        override suspend fun listModels(): List<LocalModel> = models.toList()
    }

    private class BlockingRuntime(
        private val failModelId: String? = null
    ) : LocalModelRuntime {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        val maximumConcurrent = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val loadCalls = AtomicInteger(0)
        private val releaseGate = CompletableDeferred<Unit>()
        private var generationCount = 0
        var cancelled = false
            private set

        override suspend fun load(model: LocalModel): Result<Unit> {
            loadCalls.incrementAndGet()
            if (model.id == failModelId) {
                return Result.failure(IllegalStateException("load failed"))
            }
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
            cancelled = true
            releaseGate.complete(Unit)
        }

        fun release() {
            releaseGate.complete(Unit)
        }
    }
}
