package com.example.localai

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LocalModelStore(private val context: Context) {
    private val rootDir: File by lazy {
        File(context.filesDir, "local_models").apply { mkdirs() }
    }

    fun list(): List<LocalModel> = rootDir.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { readMetadata(it) }
        ?.sortedBy { it.fileName.lowercase() }
        ?: emptyList()

    fun import(plan: LocalModelImportPlan): LocalModel {
        require(plan.displayName.isNotBlank()) { "Model name cannot be blank" }
        val source = context.contentResolver.openInputStream(plan.sourceUri)
            ?: error("Unable to open selected model file")

        val safeName = plan.displayName.substringAfterLast('/').ifBlank { "model.litertlm" }
        val id = UUID.randomUUID().toString()
        val modelDir = File(rootDir, id).apply { mkdirs() }
        val modelFile = File(modelDir, safeName)
        val metadataFile = File(modelDir, "model.json")

        try {
            source.use { input -> modelFile.outputStream().use { output -> input.copyTo(output) } }
            if (!modelFile.isFile || modelFile.length() == 0L) error("Imported model file is empty")
            val model = LocalModel(
                id = id,
                fileName = safeName,
                modelType = plan.modelType,
                configuration = plan.configuration,
                capabilities = plan.capabilities,
                accelerator = plan.accelerator,
                importedAtEpochMillis = System.currentTimeMillis()
            )
            metadataFile.writeText(toJson(model).toString())
            return model
        } catch (error: Throwable) {
            modelDir.deleteRecursively()
            throw error
        }
    }

    fun delete(modelId: String): Boolean {
        require(isSafeId(modelId)) { "Invalid model id" }
        return File(rootDir, modelId).takeIf { it.isDirectory }?.deleteRecursively() == true
    }

    fun modelFile(modelId: String): File {
        require(isSafeId(modelId)) { "Invalid model id" }
        val dir = File(rootDir, modelId)
        val metadata = File(dir, "model.json")
        val model = readMetadata(dir) ?: error("Model metadata not found")
        return File(dir, model.fileName).also {
            require(it.isFile) { "Model file not found" }
        }
    }

    private fun readMetadata(dir: File): LocalModel? = runCatching {
        val json = JSONObject(File(dir, "model.json").readText())
        LocalModel(
            id = json.getString("id"),
            fileName = json.getString("fileName"),
            modelType = ModelType.valueOf(json.optString("modelType", "UNKNOWN")),
            configuration = LocalModelConfiguration(
                maxTokens = json.getJSONObject("configuration").getInt("maxTokens"),
                topK = json.getJSONObject("configuration").getInt("topK"),
                topP = json.getJSONObject("configuration").getDouble("topP"),
                temperature = json.getJSONObject("configuration").getDouble("temperature")
            ),
            capabilities = json.getJSONObject("capabilities").let { c ->
                ModelCapabilities(
                    image = c.optBoolean("image"), audio = c.optBoolean("audio"),
                    tinyGarden = c.optBoolean("tinyGarden"), mobileActions = c.optBoolean("mobileActions"),
                    thinking = c.optBoolean("thinking"), speculativeDecoding = c.optBoolean("speculativeDecoding")
                )
            },
            accelerator = Accelerator.valueOf(json.optString("accelerator", "CPU")),
            importedAtEpochMillis = json.getLong("importedAtEpochMillis")
        )
    }.getOrNull()

    private fun toJson(model: LocalModel): JSONObject = JSONObject().apply {
        put("id", model.id)
        put("fileName", model.fileName)
        put("modelType", model.modelType.name)
        put("configuration", JSONObject().apply {
            put("maxTokens", model.configuration.maxTokens)
            put("topK", model.configuration.topK)
            put("topP", model.configuration.topP)
            put("temperature", model.configuration.temperature)
        })
        put("capabilities", JSONObject().apply {
            put("image", model.capabilities.image)
            put("audio", model.capabilities.audio)
            put("tinyGarden", model.capabilities.tinyGarden)
            put("mobileActions", model.capabilities.mobileActions)
            put("thinking", model.capabilities.thinking)
            put("speculativeDecoding", model.capabilities.speculativeDecoding)
        })
        put("accelerator", model.accelerator.name)
        put("importedAtEpochMillis", model.importedAtEpochMillis)
    }

    private fun isSafeId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,64}"))
}
