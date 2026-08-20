package com.example.capture

/**
 * UI-neutral state collected after the user selects a model and before import.
 * Keeping this separate from storage prevents a selected Uri from being copied
 * until the user explicitly confirms the configuration.
 */
data class LocalModelImportPlan(
    val sourceName: String,
    val modelType: ModelType = ModelType.UNKNOWN,
    val configuration: LocalModelConfiguration = LocalModelConfiguration(),
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val accelerator: Accelerator = Accelerator.CPU,
)
