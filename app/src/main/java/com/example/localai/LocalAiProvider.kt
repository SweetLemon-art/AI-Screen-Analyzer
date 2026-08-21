package com.example.localai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-facing Local AI provider.
 *
 * The provider owns model selection while LocalModelRuntime owns the actual
 * on-device inference lifecycle. LiteRT-LM therefore remains an implementation
 * detail of the runtime rather than leaking into callers.
 */
class LocalAiProvider(
    private val modelCatalog: LocalModelCatalog,
    private val runtime: LocalModelRuntime
) {
    private var selectedModelId: String? = null
    private val operationMutex = Mutex()

    suspend fun selectModel(modelId: String): Result<Unit> = operationMutex.withLock {
        val model = modelCatalog.listModels().firstOrNull { it.id == modelId }
            ?: return@withLock Result.failure(IllegalArgumentException("Local model not found: $modelId"))

        // LiteRtLmRuntime closes the previous engine before initializing a new
        // model. Clear the provider selection first so a failed load cannot leave
        // the provider claiming that an unavailable model is still active.
        selectedModelId = null

        runtime.load(model).onSuccess {
            selectedModelId = model.id
        }
    }

    fun generate(prompt: String): Flow<LocalAiEvent> = flow {
        operationMutex.withLock {
            if (selectedModelId == null) {
                emit(LocalAiEvent.Failed(IllegalStateException("No local model is selected")))
                return@withLock
            }

            runtime.generate(prompt).collect { event -> emit(event) }
        }
    }

    /** Generates a local multimodal response from the selected model and image bytes. */
    fun generate(prompt: String, imageBytes: ByteArray): Flow<LocalAiEvent> = flow {
        operationMutex.withLock {
            if (selectedModelId == null) {
                emit(LocalAiEvent.Failed(IllegalStateException("No local model is selected")))
                return@withLock
            }
            if (imageBytes.isEmpty()) {
                emit(LocalAiEvent.Failed(IllegalArgumentException("Image bytes must not be empty")))
                return@withLock
            }

            runtime.generate(prompt, imageBytes).collect { event -> emit(event) }
        }
    }

    suspend fun cancel() {
        // Do not take operationMutex here. Cancellation must be able to interrupt
        // the generation that currently owns the mutex.
        runtime.cancel()
    }

    suspend fun unload() = operationMutex.withLock {
        runtime.unload()
        selectedModelId = null
    }

    fun selectedModelId(): String? = selectedModelId
}
