package com.example.localai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _modelState = MutableStateFlow<LocalModelState>(LocalModelState.Idle)
    val modelState: StateFlow<LocalModelState> = _modelState.asStateFlow()

    /**
     * Selects a model safely. A model switch first requests cancellation of any
     * active generation, then waits for the operation lock before replacing the
     * runtime model. Selecting the already-loaded model is intentionally a no-op.
     */
    suspend fun selectModel(modelId: String): Result<Unit> {
        val model = modelCatalog.listModels().firstOrNull { it.id == modelId }
            ?: run {
                _modelState.value = LocalModelState.Failed(
                    IllegalArgumentException("Local model not found: $modelId")
                )
                return Result.failure(IllegalArgumentException("Local model not found: $modelId"))
            }

        if (selectedModelId == model.id && _modelState.value == LocalModelState.Ready(model.id)) {
            return Result.success(Unit)
        }

        // Cancellation deliberately does not take operationMutex, so an active
        // generation can be interrupted before the switch waits for cleanup.
        runtime.cancel()

        return operationMutex.withLock {
            if (selectedModelId == model.id && _modelState.value == LocalModelState.Ready(model.id)) {
                return@withLock Result.success(Unit)
            }

            _modelState.value = LocalModelState.Switching(fromModelId = selectedModelId, toModelId = model.id)
            selectedModelId = null

            val loadResult = runtime.load(model)
            loadResult.onSuccess {
                selectedModelId = model.id
                _modelState.value = LocalModelState.Ready(model.id)
            }.onFailure { error ->
                selectedModelId = null
                _modelState.value = LocalModelState.Failed(error)
            }
            loadResult
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

    /**
     * Cancellation must remain outside operationMutex so it can interrupt the
     * generation that currently owns the mutex.
     */
    suspend fun cancel() {
        runtime.cancel()
    }

    suspend fun unload() = operationMutex.withLock {
        runtime.unload()
        selectedModelId = null
        _modelState.value = LocalModelState.Idle
    }

    fun selectedModelId(): String? = selectedModelId
}

/** Observable lifecycle state for Local AI model ownership. */
sealed interface LocalModelState {
    data object Idle : LocalModelState
    data class Switching(val fromModelId: String?, val toModelId: String) : LocalModelState
    data class Ready(val modelId: String) : LocalModelState
    data class Failed(val error: Throwable) : LocalModelState
}
