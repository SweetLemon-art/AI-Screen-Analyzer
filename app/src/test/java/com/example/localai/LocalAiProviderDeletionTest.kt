package com.example.localai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiProviderDeletionTest {
    @Test
    fun deletingLoadedModelUnloadsBeforeStorageDelete() = runBlocking {
        val events = mutableListOf<String>()
        val model = testModel()
        val runtime = recordingRuntime(events)
        val catalog = recordingCatalog(model, events)
        val provider = LocalAiProvider(catalog, runtime)
        provider.selectModel(model.id)

        assertTrue(provider.deleteModel(model.id).isSuccess)
        assertEquals(listOf("unload", "delete"), events)
    }

    @Test
    fun unloadFailurePreventsStorageDeletion() = runBlocking {
        val events = mutableListOf<String>()
        val model = testModel()
        val runtime = recordingRuntime(events, unloadFailure = IllegalStateException("synthetic unload failure"))
        val catalog = recordingCatalog(model, events)
        val provider = LocalAiProvider(catalog, runtime)
        provider.selectModel(model.id)

        try {
            provider.deleteModel(model.id)
            throw AssertionError("Expected unload failure to be propagated")
        } catch (error: IllegalStateException) {
            assertEquals("synthetic unload failure", error.message)
        }

        assertEquals(listOf("unload"), events)
        assertFalse(catalog.deleted)
        assertEquals(model.id, provider.selectedModelId())
    }

    private fun testModel() = LocalModel(
        id = "model-1",
        fileName = "model.litertlm",
        modelType = ModelType.LLM,
        configuration = LocalModelConfiguration(),
        capabilities = ModelCapabilities(),
        accelerator = Accelerator.CPU,
        importedAtEpochMillis = 1L
    )

    private fun recordingRuntime(
        events: MutableList<String>,
        unloadFailure: Throwable? = null
    ) = object : LocalModelRuntime {
        override suspend fun load(model: LocalModel): Result<Unit> = Result.success(Unit)
        override fun generate(prompt: String) = kotlinx.coroutines.flow.emptyFlow<LocalAiEvent>()
        override fun generate(prompt: String, imageBytes: ByteArray) = kotlinx.coroutines.flow.emptyFlow<LocalAiEvent>()
        override suspend fun unload() {
            events += "unload"
            unloadFailure?.let { throw it }
        }
        override suspend fun cancel() = Unit
    }

    private fun recordingCatalog(
        model: LocalModel,
        events: MutableList<String>
    ) = object : LocalModelCatalog, LocalModelDeletionCatalog {
        var deleted = false

        override suspend fun listModels() = listOf(model)

        override suspend fun deleteModel(modelId: String): Result<Unit> {
            events += "delete"
            deleted = true
            return Result.success(Unit)
        }
    }
}
