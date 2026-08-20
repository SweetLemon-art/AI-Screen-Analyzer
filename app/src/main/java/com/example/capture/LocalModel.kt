package com.example.capture

/**
 * Generic metadata for a locally imported LiteRT-LM model.
 *
 * Capabilities are informational only. The model manager never rejects a model
 * because a capability is unavailable; individual AI features decide whether
 * a model is suitable for their use case.
 */
data class LocalModel(
    val id: String,
    val fileName: String,
    val modelType: ModelType,
    val configuration: LocalModelConfiguration,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val accelerator: Accelerator = Accelerator.CPU,
)

enum class ModelType {
    LLM,
    UNKNOWN,
}

enum class Accelerator {
    CPU,
    GPU,
    NPU,
}

data class LocalModelConfiguration(
    val maxTokens: Int = 1024,
    val topK: Int = 64,
    val topP: Double = 0.95,
    val temperature: Double = 1.0,
)

data class ModelCapabilities(
    val image: Boolean = false,
    val audio: Boolean = false,
    val thinking: Boolean = false,
    val speculativeDecoding: Boolean = false,
    val mobileActions: Boolean = false,
    val tinyGarden: Boolean = false,
)
