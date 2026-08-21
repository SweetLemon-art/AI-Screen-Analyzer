package com.example.localai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

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

    suspend fun selectModel(modelId: String): Result<Unit> {
        val model = modelCatalog.listModels().firstOrNull { it.id == modelId }
            ?: return Result.failure(IllegalArgumentException("Local model not found: $modelId"))

        // LiteRtLmRuntime closes the previous engine before initializing a new
        // model. Clear the provider selection first so a failed load cannot leave
        // the provider claiming that an unavailable model is still active.
        selectedModelId = null

        return runtime.load(model).onSuccess {
            selectedModelId = model.id
        }
    }

    fun generate(prompt: String): Flow<LocalAiEvent> = flow {
        if (selectedModelId == null) {
            emit(LocalAiEvent.Failed(IllegalStateException("No local model is selected")))
            return@flow
        }

        runtime.generate(prompt).collect { event -> emit(event) }
    }

    suspend fun cancel() {
        runtime.cancel()
    }

    suspend fun unload() {
        runtime.unload()
        selectedModelId = null
    }

    fun selectedModelId(): String? = selectedModelId
}
