package com.example.localai

/**
 * Read-only source of locally installed models for provider selection.
 * Keeping this contract separate from storage/import operations keeps the
 * Local AI provider independent from the filesystem implementation.
 */
interface LocalModelCatalog {
    suspend fun listModels(): List<LocalModel>
}
