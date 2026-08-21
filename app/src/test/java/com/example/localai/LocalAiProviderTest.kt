package com.example.localai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiProviderTest {
    private val model = LocalModel(
        id = "model-1",
        fileName = "model.litertlm",
        modelType = ModelType.LLM,
        configuration = LocalModelConfiguration(),
        capabilities = ModelCapabilities(),
        accelerator = Accelerator.CPU,
        importedAtEpochMillis = 1L
    )

    private val visionModel = model.copy(
        id = "vision-model",
        capabilities = ModelCapabilities(image = true)
    )

    @Test
    fun selectModelLoadsInstalledModel() = runBlocking {
        val runtime = FakeRuntime()
        val provider = LocalAiProvider(FakeCatalog(listOf(model)), runtime)

        val result = provider.selectModel("model-1")

        assertTrue(result.isSuccess)
        assertEquals("model-1", provider.selectedModelId())
        assertEquals("model-1", runtime.loadedModel?.id)
    }

    @Test
    fun selectModelRejectsUnknownModelWithoutCallingRuntime() = runBlocking {
        val runtime = FakeRuntime()
        val provider = LocalAiProvider(FakeCatalog(emptyList()), runtime)

        val result = provider.selectModel("missing")

        assertTrue(result.isFailure)
        assertEquals(null, provider.selectedModelId())
        assertEquals(null, runtime.loadedModel)
    }

    @Test
    fun generateFailsWhenNoModelIsSelected() = runBlocking {
        val provider = LocalAiProvider(FakeCatalog(listOf(model)), FakeRuntime())

        val events = provider.generate("hello").toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is LocalAiEvent.Failed)
    }

    @Test
    fun generateDelegatesToRuntimeAfterSelection() = runBlocking {
        val runtime = FakeRuntime()
        val provider = LocalAiProvider(FakeCatalog(listOf(model)), runtime)
        provider.selectModel("model-1")

        val events = provider.generate("hello").toList()

        assertEquals(
            listOf(LocalAiEvent.Started, LocalAiEvent.Token("hello"), LocalAiEvent.Completed),
            events
        )
        assertEquals("hello", runtime.lastPrompt)
    }

    @Test
    fun generateImageDelegatesPromptAndBytesToRuntime() = runBlocking {
        val runtime = FakeRuntime()
        val provider = LocalAiProvider(FakeCatalog(listOf(visionModel)), runtime)
        provider.selectModel("vision-model")
        val image = byteArrayOf(1, 2, 3, 4)

        val events = provider.generate("describe this", image).toList()

        assertEquals(
            listOf(LocalAiEvent.Started, LocalAiEvent.Token("describe this"), LocalAiEvent.Completed),
            events
        )
        assertEquals("describe this", runtime.lastPrompt)
        assertTrue(runtime.lastImageBytes!!.contentEquals(image))
    }

    @Test
    fun generateImageRejectsEmptyBytes() = runBlocking {
        val runtime = FakeRuntime()
        val provider = LocalAiProvider(FakeCatalog(listOf(visionModel)), runtime)
        provider.selectModel("vision-model")

        val events = provider.generate("describe this", byteArrayOf()).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is LocalAiEvent.Failed)
    }

    @Test
    fun unloadClearsSelectedModelAndDelegates() = runBlocking {
        val runtime = FakeRuntime()
        val provider = LocalAiProvider(FakeCatalog(listOf(model)), runtime)
        provider.selectModel("model-1")

        provider.unload()

        assertEquals(null, provider.selectedModelId())
        assertTrue(runtime.unloaded)
    }

    private class FakeCatalog(
        private val models: List<LocalModel>
    ) : LocalModelCatalog {
        override suspend fun listModels(): List<LocalModel> = models
    }

    private class FakeRuntime : LocalModelRuntime {
        var loadedModel: LocalModel? = null
        var lastPrompt: String? = null
        var lastImageBytes: ByteArray? = null
        var unloaded = false

        override suspend fun load(model: LocalModel): Result<Unit> {
            loadedModel = model
            return Result.success(Unit)
        }

        override fun generate(prompt: String): Flow<LocalAiEvent> {
            lastPrompt = prompt
            lastImageBytes = null
            return response(prompt)
        }

        override fun generate(prompt: String, imageBytes: ByteArray): Flow<LocalAiEvent> {
            lastPrompt = prompt
            lastImageBytes = imageBytes
            return response(prompt)
        }

        private fun response(prompt: String): Flow<LocalAiEvent> = flowOf(
            LocalAiEvent.Started,
            LocalAiEvent.Token(prompt),
            LocalAiEvent.Completed
        )

        override suspend fun unload() {
            unloaded = true
            loadedModel = null
        }

        override suspend fun cancel() = Unit
    }
}
