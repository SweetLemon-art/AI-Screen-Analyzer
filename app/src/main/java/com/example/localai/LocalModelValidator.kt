package com.example.localai

import java.util.Locale

/** Pure validation for user-supplied local LiteRT-LM import settings. */
object LocalModelValidator {
    private const val MAX_FILE_NAME_LENGTH = 255

    fun validate(plan: LocalModelImportPlan) {
        validateFileName(plan.displayName)
        validateConfiguration(plan.configuration)
    }

    fun validateFileName(fileName: String) {
        val normalized = fileName.trim()
        require(normalized.isNotEmpty()) { "Model file name cannot be blank" }
        require(normalized.length <= MAX_FILE_NAME_LENGTH) {
            "Model file name is too long"
        }
        require(normalized == normalized.substringAfterLast('/')) {
            "Model file name must not contain path separators"
        }
        require(normalized == normalized.substringAfterLast('\\')) {
            "Model file name must not contain path separators"
        }
        require(normalized.lowercase(Locale.ROOT).endsWith(".litertlm")) {
            "Only .litertlm model files are supported"
        }
    }

    fun validateConfiguration(configuration: LocalModelConfiguration) {
        require(configuration.maxTokens in 100..4096) {
            "Max tokens must be 100..4096"
        }
        require(configuration.topK in 1..100) {
            "Top K must be 1..100"
        }
        require(configuration.topP in 0.0..1.0) {
            "Top P must be 0..1"
        }
        require(configuration.temperature in 0.0..2.0) {
            "Temperature must be 0..2"
        }
    }
}
