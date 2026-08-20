package com.example.capture

import android.content.Context
import java.io.IOException

/** Application-facing repository for generic local models. */
class LocalModelRepository(context: Context) {
    private val store = LocalModelStore(context.applicationContext)

    fun models(): List<LocalModel> = store.list()

    @Throws(IOException::class)
    fun importModel(
        fileName: String,
        source: java.io.InputStream,
        configuration: LocalModelConfiguration,
        modelType: ModelType = ModelType.UNKNOWN,
        capabilities: ModelCapabilities = ModelCapabilities(),
        accelerator: Accelerator = Accelerator.CPU,
    ): LocalModel {
        LocalModelValidator.validateFileName(fileName).getOrThrow()
        LocalModelValidator.validateConfiguration(configuration).getOrThrow()

        val model = LocalModel(
            id = store.generateId(),
            fileName = fileName,
            modelType = modelType,
            configuration = configuration,
            capabilities = capabilities,
            accelerator = accelerator,
        )
        return store.importModel(fileName, source, model)
    }

    fun modelFile(modelId: String) = store.modelFile(modelId)

    fun delete(modelId: String) = store.delete(modelId)
}
