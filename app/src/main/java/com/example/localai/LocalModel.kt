package com.example.localai

import android.net.Uri

/** Generic local model metadata. Capability flags are descriptive only. */
data class LocalModel(
    val id: String,
    val fileName: String,
    val modelType: ModelType,
    val configuration: LocalModelConfiguration,
    val capabilities: ModelCapabilities,
    val accelerator: Accelerator,
    val importedAtEpochMillis: Long
)

enum class ModelType { LLM, UNKNOWN }
enum class Accelerator { CPU, GPU, NPU }

data class LocalModelConfiguration(
    val maxTokens: Int = 1024,
    val topK: Int = 64,
    val topP: Double = 0.95,
    val temperature: Double = 1.0
)

data class ModelCapabilities(
    val image: Boolean = false,
    val audio: Boolean = false,
    val tinyGarden: Boolean = false,
    val mobileActions: Boolean = false,
    val thinking: Boolean = false,
    val speculativeDecoding: Boolean = false
)

data class LocalModelImportPlan(
    val sourceUri: Uri,
    val displayName: String,
    val modelType: ModelType = ModelType.LLM,
    val configuration: LocalModelConfiguration = LocalModelConfiguration(),
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val accelerator: Accelerator = Accelerator.CPU
)
