package com.example.localai

import android.content.Context
import android.net.Uri
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

object LocalModelValidator {
    private val supportedExtension = Regex("(?i)\\.litertlm$")

    fun validate(plan: LocalModelImportPlan) {
        require(plan.displayName.matches(supportedExtension)) {
            "Only .litertlm model files are supported"
        }
        require(plan.configuration.maxTokens in 100..4096) { "Max tokens must be 100..4096" }
        require(plan.configuration.topK in 1..100) { "Top K must be 1..100" }
        require(plan.configuration.topP in 0.0..1.0) { "Top P must be 0..1" }
        require(plan.configuration.temperature in 0.0..2.0) { "Temperature must be 0..2" }
    }
}
