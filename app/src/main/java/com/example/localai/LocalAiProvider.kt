package com.example.localai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

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
    private val selectedModelId = AtomicReference<String?>(null)
    private val operationMutex = Mutex()
    private val _modelState = MutableStateFlow<LocalModelState>(LocalModelState.Idle)
    val modelState: StateFlow<LocalModelState> = _modelState.asStateFlow()

    suspend fun selectModel(modelId: String): Result<Unit> {
        val model = modelCatalog.listModels().firstOrNull { it.id == modelId }
            ?: run {
                val error = IllegalArgumentException("Local model not found: $modelId")
                _modelState.value = LocalModelState.Failed(error)
                return Result.failure(error)
            }

        if (selectedModelId.get() == model.id && _modelState.value == LocalModelState.Ready(model.id)) {
            return Result.success(Unit)
        }

        runtime.cancel()

        return operationMutex.withLock {
            if (selectedModelId.get() == model.id && _modelState.value == LocalModelState.Ready(model.id)) {
                return@withLock Result.success(Unit)
            }

            _modelState.value = LocalModelState.Switching(
                fromModelId = selectedModelId.get(),
                toModelId = model.id
            )
            selectedModelId.set(null)

            val loadResult = runtime.load(model)
            loadResult.onSuccess {
                selectedModelId.set(model.id)
                _modelState.value = LocalModelState.Ready(model.id)
            }.onFailure { error ->
                selectedModelId.set(null)
                _modelState.value = LocalModelState.Failed(error)
            }
            loadResult
        }
    }

    fun generate(prompt: String): Flow<LocalAiEvent> = flow {
        operationMutex.withLock {
            if (selectedModelId.get() == null) {
                emit(LocalAiEvent.Failed(IllegalStateException("No local model is selected")))
                return@withLock
            }
            runtime.generate(prompt).collect { event -> emit(event) }
        }
    }

    fun generate(prompt: String, imageBytes: ByteArray): Flow<LocalAiEvent> = flow {
        operationMutex.withLock {
            if (selectedModelId.get() == null) {
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
        runtime.cancel()
    }

    suspend fun unload() = operationMutex.withLock {
        runtime.unload()
        selectedModelId.set(null)
        _modelState.value = LocalModelState.Idle
    }

    /** Deletes a model only after unloading it when it owns the active runtime. */
    suspend fun deleteModel(modelId: String): Result<Unit> {
        require(modelCatalog is LocalModelDeletionCatalog) {
            "Local model catalog does not support deletion"
        }

        return operationMutex.withLock {
            if (selectedModelId.get() == modelId) {
                runtime.unload()
                selectedModelId.set(null)
                _modelState.value = LocalModelState.Idle
            }
            modelCatalog.deleteModel(modelId)
        }
    }

    fun selectedModelId(): String? = selectedModelId.get()
}

sealed interface LocalModelState {
    data object Idle : LocalModelState
    data class Switching(val fromModelId: String?, val toModelId: String) : LocalModelState
    data class Ready(val modelId: String) : LocalModelState
    data class Failed(val error: Throwable) : LocalModelState
}
