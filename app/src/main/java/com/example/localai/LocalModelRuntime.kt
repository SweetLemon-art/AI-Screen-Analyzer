package com.example.localai

import kotlinx.coroutines.flow.Flow

/**
 * Provider-neutral contract for on-device model inference.
 * The UI and future Ask AI features depend on this abstraction rather than LiteRT-LM directly.
 */
interface LocalModelRuntime {
    suspend fun load(model: LocalModel): Result<Unit>

    fun generate(prompt: String): Flow<LocalAiEvent>

    suspend fun unload()

    suspend fun cancel()
}

sealed interface LocalAiEvent {
    data object Started : LocalAiEvent
    data class Token(val text: String) : LocalAiEvent
    data object Completed : LocalAiEvent
    data class Failed(val error: Throwable) : LocalAiEvent
}
