package com.example.localai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalModelRepository(context: Context) {
    private val store = LocalModelStore(context.applicationContext)

    suspend fun listModels(): List<LocalModel> = withContext(Dispatchers.IO) { store.list() }

    suspend fun importModel(plan: LocalModelImportPlan): Result<LocalModel> = withContext(Dispatchers.IO) {
        runCatching {
            LocalModelValidator.validate(plan)
            store.import(plan)
        }
    }

    suspend fun deleteModel(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(store.delete(modelId)) { "Model not found" }
        }
    }

    suspend fun modelFile(modelId: String) = withContext(Dispatchers.IO) {
        store.modelFile(modelId)
    }
}
