package com.example.capture

import java.io.File

/** Lightweight validation used by the import flow before copying a model. */
object LocalModelValidator {
    fun validateFileName(fileName: String): Result<Unit> {
        if (fileName.isBlank()) return Result.failure(IllegalArgumentException("Model file name is empty"))
        if (!fileName.endsWith(".litertlm", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Only .litertlm model files are supported"))
        }
        return Result.success(Unit)
    }

    fun validateConfiguration(configuration: LocalModelConfiguration): Result<Unit> {
        if (configuration.maxTokens !in 100..4096) {
            return Result.failure(IllegalArgumentException("Max tokens must be between 100 and 4096"))
        }
        if (configuration.topK !in 1..100) {
            return Result.failure(IllegalArgumentException("Top K must be between 1 and 100"))
        }
        if (configuration.topP !in 0.0..1.0) {
            return Result.failure(IllegalArgumentException("Top P must be between 0 and 1"))
        }
        if (configuration.temperature !in 0.0..2.0) {
            return Result.failure(IllegalArgumentException("Temperature must be between 0 and 2"))
        }
        return Result.success(Unit)
    }

    fun validateModelFile(file: File): Result<Unit> {
        if (!file.isFile) return Result.failure(IllegalArgumentException("Model file does not exist"))
        if (!file.canRead()) return Result.failure(IllegalArgumentException("Model file is not readable"))
        return Result.success(Unit)
    }
}
