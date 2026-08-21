package com.example.localai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiProviderDeletionTest {
    @Test
    fun deletingLoadedModelUnloadsBeforeStorageDelete() = runBlocking {
        val events = mutableListOf<String>()
        val model = LocalModel(
            id = "model-1",
            fileName = "model.litertlm",
            modelType = ModelType.LLM,
            configuration = LocalModelConfiguration(),
            capabilities = ModelCapabilities(),
            accelerator = Accelerator.CPU,
            importedAtEpochMillis = 1L
        )
        val runtime = object : LocalModelRuntime {
            override suspend fun load(model: LocalModel): Result<Unit> = Result.success(Unit)
            override fun generate(prompt: String) = kotlinx.coroutines.flow.emptyFlow<LocalAiEvent>()
            override fun generate(prompt: String, imageBytes: ByteArray) = kotlinx.coroutines.flow.emptyFlow<LocalAiEvent>()
            override suspend fun unload() { events += "unload" }
            override suspend fun cancel() = Unit
        }
        val catalog = object : LocalModelCatalog, LocalModelDeletionCatalog {
            override suspend fun listModels() = listOf(model)
            override suspend fun deleteModel(modelId: String): Result<Unit> {
                events += "delete"
                return Result.success(Unit)
            }
        }
        val provider = LocalAiProvider(catalog, runtime)
        provider.selectModel(model.id)

        assertTrue(provider.deleteModel(model.id).isSuccess)
        assertEquals(listOf("unload", "delete"), events)
    }
}
