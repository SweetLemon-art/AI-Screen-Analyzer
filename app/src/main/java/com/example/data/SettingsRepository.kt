package com.example.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages persistence for CaptureSettings and AnalysisContexts.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun loadSettings(): CaptureSettings {
        val delay = prefs.getInt(KEY_DELAY, CaptureSettings.DEFAULT.delaySeconds).coerceIn(1, 600)
        val resolution = prefs.getInt(KEY_RESOLUTION, CaptureSettings.DEFAULT.maxResolutionDimension)
        val quality = prefs.getInt(KEY_QUALITY, CaptureSettings.DEFAULT.compressionQuality).coerceIn(40, 100)

        return CaptureSettings(
            delaySeconds = delay,
            maxResolutionDimension = resolution,
            compressionQuality = quality
        )
    }

    fun saveSettings(settings: CaptureSettings) {
        prefs.edit()
            .putInt(KEY_DELAY, settings.delaySeconds.coerceIn(1, 600))
            .putInt(KEY_RESOLUTION, settings.maxResolutionDimension)
            .putInt(KEY_QUALITY, settings.compressionQuality.coerceIn(40, 100))
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

    companion object {
        private const val PREFS_NAME = "ai_screen_analyzer_settings"
        private const val KEY_DELAY = "capture_delay_seconds"
        private const val KEY_RESOLUTION = "max_resolution_dimension"
        private const val KEY_QUALITY = "compression_quality"
        private const val KEY_SAVED_CONTEXTS = "saved_contexts_json"
        private const val KEY_SELECTED_CONTEXT_ID = "selected_context_id"
    }
}
