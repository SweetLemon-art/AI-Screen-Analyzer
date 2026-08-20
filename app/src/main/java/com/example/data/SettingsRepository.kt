package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.ai.GeminiModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages persistence for CaptureSettings, AnalysisContexts, and Gemini Model preferences.
 * Strictly guarantees all retrieved and stored values are clamped within safe ranges.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun loadSettings(): CaptureSettings {
        val delay = prefs.getInt(KEY_DELAY, CaptureSettings.DEFAULT.delaySeconds).coerceIn(1, 600)
        val resolution = prefs.getInt(KEY_RESOLUTION, CaptureSettings.DEFAULT.maxResolutionDimension).coerceIn(480, 2160)
        val quality = prefs.getInt(KEY_QUALITY, CaptureSettings.DEFAULT.compressionQuality).coerceIn(40, 100)

        return CaptureSettings(
            delaySeconds = delay,
            maxResolutionDimension = resolution,
            compressionQuality = quality
        )
    }

    fun saveSettings(settings: CaptureSettings) {
        val safeSettings = CaptureSettings.createSafe(
            delay = settings.delaySeconds,
            resolution = settings.maxResolutionDimension,
            quality = settings.compressionQuality
        )
        prefs.edit()
            .putInt(KEY_DELAY, safeSettings.delaySeconds)
            .putInt(KEY_RESOLUTION, safeSettings.maxResolutionDimension)
            .putInt(KEY_QUALITY, safeSettings.compressionQuality)
            .apply()
    }

    fun loadContexts(): List<AnalysisContext> {
        val jsonString = prefs.getString(KEY_SAVED_CONTEXTS, null) ?: return AnalysisContext.DEFAULT_PRESETS

        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<AnalysisContext>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    AnalysisContext(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        instructions = obj.getString("instructions"),
                        language = obj.optString("language", "English"),
                        isPreset = obj.optBoolean("isPreset", false)
                    )
                )
            }
            if (list.isEmpty()) AnalysisContext.DEFAULT_PRESETS else list
        } catch (e: Exception) {
            AnalysisContext.DEFAULT_PRESETS
        }
    }

    fun saveContexts(contexts: List<AnalysisContext>) {
        val jsonArray = JSONArray()
        contexts.forEach { ctx ->
            val obj = JSONObject().apply {
                put("id", ctx.id)
                put("name", ctx.name)
                put("instructions", ctx.instructions)
                put("language", ctx.language)
                put("isPreset", ctx.isPreset)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_SAVED_CONTEXTS, jsonArray.toString()).apply()
    }

    fun loadSelectedContextId(): String {
        return prefs.getString(KEY_SELECTED_CONTEXT_ID, AnalysisContext.DEFAULT.id) ?: AnalysisContext.DEFAULT.id
    }

    fun saveSelectedContextId(id: String) {
        prefs.edit().putString(KEY_SELECTED_CONTEXT_ID, id).apply()
    }

    fun loadSelectedModel(): String {
        return com.example.ai.normalizeModelId(prefs.getString(KEY_SELECTED_MODEL, ""))
    }

    fun saveSelectedModel(model: String) {
        val cleanModel = com.example.ai.normalizeModelId(model)
        if (cleanModel.isBlank()) {
            clearSelectedModel()
        } else {
            prefs.edit().putString(KEY_SELECTED_MODEL, cleanModel).apply()
        }
    }

    fun clearSelectedModel() {
        prefs.edit().remove(KEY_SELECTED_MODEL).apply()
    }

    fun loadDiscoveredModels(): List<GeminiModel> {
        val jsonString = prefs.getString(KEY_DISCOVERED_MODELS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<GeminiModel>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val methodsArray = obj.optJSONArray("methods")
                val methods = mutableListOf<String>()
                if (methodsArray != null) {
                    for (j in 0 until methodsArray.length()) {
                        methods.add(methodsArray.optString(j))
                    }
                }
                val inputLimit = if (obj.has("inputTokenLimit")) obj.optInt("inputTokenLimit") else null
                val outputLimit = if (obj.has("outputTokenLimit")) obj.optInt("outputTokenLimit") else null
                val version = if (obj.has("version") && !obj.isNull("version")) obj.optString("version") else null
                val baseModelId = if (obj.has("baseModelId") && !obj.isNull("baseModelId")) obj.optString("baseModelId") else null

                list.add(
                    GeminiModel(
                        name = obj.getString("name"),
                        displayName = obj.optString("displayName", obj.getString("name")),
                        description = obj.optString("description", ""),
                        supportedGenerationMethods = methods,
                        inputTokenLimit = inputLimit,
                        outputTokenLimit = outputLimit,
                        version = version,
                        baseModelId = baseModelId
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveDiscoveredModels(models: List<GeminiModel>) {
        val jsonArray = JSONArray()
        models.forEach { m ->
            val obj = JSONObject().apply {
                put("name", m.name)
                put("displayName", m.displayName)
                put("description", m.description)
                val methodsArr = JSONArray()
                m.supportedGenerationMethods.forEach { methodsArr.put(it) }
                put("methods", methodsArr)
                m.inputTokenLimit?.let { put("inputTokenLimit", it) }
                m.outputTokenLimit?.let { put("outputTokenLimit", it) }
                m.version?.let { put("version", it) }
                m.baseModelId?.let { put("baseModelId", it) }
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_DISCOVERED_MODELS, jsonArray.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "ai_screen_analyzer_settings"
        private const val KEY_DELAY = "capture_delay_seconds"
        private const val KEY_RESOLUTION = "max_resolution_dimension"
        private const val KEY_QUALITY = "compression_quality"
        private const val KEY_SAVED_CONTEXTS = "saved_contexts_json"
        private const val KEY_SELECTED_CONTEXT_ID = "selected_context_id"
        private const val KEY_SELECTED_MODEL = "selected_gemini_model"
        private const val KEY_DISCOVERED_MODELS = "discovered_gemini_models"
    }
}
