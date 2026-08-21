package com.example.localai

/** Storage contract for deleting an installed local model. */
interface LocalModelDeletionCatalog {
    suspend fun deleteModel(modelId: String): Result<Unit>
}
