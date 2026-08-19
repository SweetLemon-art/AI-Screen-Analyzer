package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import kotlinx.coroutines.flow.StateFlow

sealed class ConnectionTestResult {
    data class Success(val message: String, val models: List<GeminiModel> = emptyList()) : ConnectionTestResult()
    data class Error(val message: String) : ConnectionTestResult()
}

/**
 * Representation of a Gemini model discovered dynamically via models.list API.
 */
data class GeminiModel(
    val name: String, // e.g. "models/gemini-2.5-flash"
    val displayName: String,
    val description: String,
    val supportedGenerationMethods: List<String>
) {
    val modelId: String
        get() = name.removePrefix("models/")
}

/**
 * Real client-observed quota and rate-limit metadata.
 * Note: Only actual headers and status codes returned by Gemini API are exposed.
 * No artificial local limits or local request counters are used.
 */
data class GeminiQuotaInfo(
    val status: String = "Not Configured", // "Connected", "Not Configured", "Error"
    val quota: String = "Unknown",         // "Available", "Limited", "Unknown"
    val rateLimit: String = "Normal",      // "Normal", "Limited"
    val lastQuotaError: String = "None",
    val retryAfterSeconds: Int? = null
)

interface VisionAnalyzer {
    val quotaInfo: StateFlow<GeminiQuotaInfo>

    /**
     * Sends the captured screen bitmap together with the user-defined analysis context
     * and capture settings to the multimodal AI model.
     */
    suspend fun analyze(
        bitmap: Bitmap,
        context: AnalysisContext,
        settings: CaptureSettings = CaptureSettings.DEFAULT
    ): AnalysisResult

    /**
     * Verifies the configured API key by calling models.list dynamically.
     */
    suspend fun testConnection(): ConnectionTestResult

    /**
     * Discovers available models supporting generateContent from Gemini API.
     */
    suspend fun discoverModels(): Result<List<GeminiModel>>
}
