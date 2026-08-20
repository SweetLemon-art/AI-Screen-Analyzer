package com.example.localai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

class LocalModelStore(private val context: Context) {
    private companion object {
        const val TEMP_PREFIX = ".tmp-"
        const val STALE_TEMP_AGE_MILLIS = 24 * 60 * 60 * 1000L
    }

    private val rootDir: File by lazy {
        File(context.filesDir, "local_models").apply { mkdirs() }
    }

    fun list(): List<LocalModel> {
        cleanupStaleTempDirectories()
        return rootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(TEMP_PREFIX) }
            ?.mapNotNull { readMetadata(it) }
            ?.sortedBy { it.fileName.lowercase() }
            ?: emptyList()
    }

    fun import(plan: LocalModelImportPlan): LocalModel {
        LocalModelValidator.validate(plan)
        val source = context.contentResolver.openInputStream(plan.sourceUri)
            ?: error("Unable to open selected model file")
        return import(plan, source)
    }

    /**
     * Storage-only import entry point used by JVM tests so they do not depend on a
     * platform ContentResolver implementation for a synthetic file:// Uri.
     */
    internal fun import(plan: LocalModelImportPlan, source: InputStream): LocalModel {
        LocalModelValidator.validate(plan)

        val safeName = plan.displayName.trim()
        val id = UUID.randomUUID().toString()
        val tempDir = File(rootDir, "$TEMP_PREFIX$id")
        val finalDir = File(rootDir, id)
        val modelFile = File(tempDir, safeName)
        val metadataFile = File(tempDir, "model.json")

        check(tempDir.mkdirs()) { "Unable to create temporary model directory" }

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

            check(tempDir.renameTo(finalDir)) { "Unable to finalize imported model" }
            return model
        } catch (error: Throwable) {
            tempDir.deleteRecursively()
            finalDir.deleteRecursively()
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
        val model = readMetadata(dir) ?: error("Model metadata not found")
        require(model.id == dir.name) { "Model metadata id mismatch" }
        LocalModelValidator.validateFileName(model.fileName)
        return File(dir, model.fileName).also {
            require(it.isFile) { "Model file not found" }
        }
    }

    private fun cleanupStaleTempDirectories() {
        val cutoff = System.currentTimeMillis() - STALE_TEMP_AGE_MILLIS
        rootDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(TEMP_PREFIX) && it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }

    private fun readMetadata(dir: File): LocalModel? = runCatching {
        val json = JSONObject(File(dir, "model.json").readText())
        val model = LocalModel(
            id = json.getString("id"),
            fileName = json.getString("fileName"),
            modelType = ModelType.valueOf(json.optString("modelType", "UNKNOWN")),
            configuration = json.getJSONObject("configuration").let { c ->
                LocalModelConfiguration(
                    maxTokens = c.getInt("maxTokens"),
                    topK = c.getInt("topK"),
                    topP = c.getDouble("topP"),
                    temperature = c.getDouble("temperature")
                )
            },
            capabilities = json.getJSONObject("capabilities").let { c ->
                ModelCapabilities(
                    image = c.optBoolean("image"),
                    audio = c.optBoolean("audio"),
                    tinyGarden = c.optBoolean("tinyGarden"),
                    mobileActions = c.optBoolean("mobileActions"),
                    thinking = c.optBoolean("thinking"),
                    speculativeDecoding = c.optBoolean("speculativeDecoding")
                )
            },
            accelerator = Accelerator.valueOf(json.optString("accelerator", "CPU")),
            importedAtEpochMillis = json.getLong("importedAtEpochMillis")
        )
        require(isSafeId(model.id)) { "Invalid model metadata id" }
        LocalModelValidator.validateFileName(model.fileName)
        require(model.id == dir.name) { "Model metadata id mismatch" }
        model
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
