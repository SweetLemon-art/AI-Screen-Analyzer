package com.example.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Private app storage for imported local models and their editable metadata.
 * The source Uri is intentionally not persisted: after import the app owns its copy.
 */
class LocalModelStore(private val context: Context) {
    private val rootDir: File
        get() = File(context.filesDir, MODELS_DIRECTORY)

    fun list(): List<LocalModel> = rootDir.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { directory ->
            runCatching { readMetadata(File(directory, METADATA_FILE)) }.getOrNull()
        }
        ?.sortedBy { it.id }
        ?: emptyList()

    fun importModel(
        sourceName: String,
        source: java.io.InputStream,
        model: LocalModel,
    ): LocalModel {
        require(sourceName.isNotBlank()) { "Model file name must not be blank" }
        require(model.id.isNotBlank()) { "Model id must not be blank" }
        require(model.fileName == sourceName) { "Model metadata file name must match the selected file" }

        rootDir.mkdirs()
        val directory = File(rootDir, model.id)
        require(!directory.exists()) { "A model with id ${model.id} already exists" }
        require(directory.mkdirs()) { "Unable to create model directory" }

        val modelFile = File(directory, MODEL_FILE)
        try {
            source.use { input -> modelFile.outputStream().use { output -> input.copyTo(output) } }
            writeMetadata(File(directory, METADATA_FILE), model)
            return model
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw IOException("Unable to import local model", error)
        }
    }

    fun delete(modelId: String) {
        val directory = File(rootDir, modelId)
        require(directory.canonicalFile.parentFile == rootDir.canonicalFile) {
            "Invalid model id"
        }
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("Unable to delete local model $modelId")
        }
    }

    fun modelFile(modelId: String): File = modelDirectory(modelId).resolve(MODEL_FILE)

    fun generateId(): String = UUID.randomUUID().toString()

    private fun modelDirectory(modelId: String): File {
        val directory = File(rootDir, modelId)
        require(directory.canonicalFile.parentFile == rootDir.canonicalFile) {
            "Invalid model id"
        }
        require(directory.isDirectory) { "Local model does not exist: $modelId" }
        return directory
    }

    private fun writeMetadata(file: File, model: LocalModel) {
        val json = JSONObject()
            .put("id", model.id)
            .put("fileName", model.fileName)
            .put("modelType", model.modelType.name)
            .put("accelerator", model.accelerator.name)
            .put("configuration", JSONObject()
                .put("maxTokens", model.configuration.maxTokens)
                .put("topK", model.configuration.topK)
                .put("topP", model.configuration.topP)
                .put("temperature", model.configuration.temperature))
            .put("capabilities", JSONObject()
                .put("image", model.capabilities.image)
                .put("audio", model.capabilities.audio)
                .put("thinking", model.capabilities.thinking)
                .put("speculativeDecoding", model.capabilities.speculativeDecoding)
                .put("mobileActions", model.capabilities.mobileActions)
                .put("tinyGarden", model.capabilities.tinyGarden))
        file.writeText(json.toString(2))
    }

    private fun readMetadata(file: File): LocalModel {
        val json = JSONObject(file.readText())
        val configuration = json.optJSONObject("configuration") ?: JSONObject()
        val capabilities = json.optJSONObject("capabilities") ?: JSONObject()
        return LocalModel(
            id = json.getString("id"),
            fileName = json.getString("fileName"),
            modelType = runCatching { ModelType.valueOf(json.optString("modelType", ModelType.UNKNOWN.name)) }
                .getOrDefault(ModelType.UNKNOWN),
            accelerator = runCatching { Accelerator.valueOf(json.optString("accelerator", Accelerator.CPU.name)) }
                .getOrDefault(Accelerator.CPU),
            configuration = LocalModelConfiguration(
                maxTokens = configuration.optInt("maxTokens", 1024),
                topK = configuration.optInt("topK", 64),
                topP = configuration.optDouble("topP", 0.95),
                temperature = configuration.optDouble("temperature", 1.0),
            ),
            capabilities = ModelCapabilities(
                image = capabilities.optBoolean("image"),
                audio = capabilities.optBoolean("audio"),
                thinking = capabilities.optBoolean("thinking"),
                speculativeDecoding = capabilities.optBoolean("speculativeDecoding"),
                mobileActions = capabilities.optBoolean("mobileActions"),
                tinyGarden = capabilities.optBoolean("tinyGarden"),
            ),
        )
    }

    companion object {
        private const val MODELS_DIRECTORY = "local_models"
        private const val MODEL_FILE = "model.litertlm"
        private const val METADATA_FILE = "model.json"
    }
}
