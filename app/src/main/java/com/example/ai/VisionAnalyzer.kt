package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import kotlinx.coroutines.flow.StateFlow

sealed class ConnectionTestResult {
    data class Success(val message: String, val models: List<GeminiModel> = emptyList()) : ConnectionTestResult()
    data class Error(val message: String) : ConnectionTestResult()
}

enum class RateLimitState {
    UNKNOWN,
    NORMAL,
    RATE_LIMITED
}

/**
 * Representation of a Gemini model discovered dynamically via models.list API.
 */
data class GeminiModel(
    val name: String, // e.g. "models/gemini-2.5-flash"
    val displayName: String,
    val description: String,
    val supportedGenerationMethods: List<String>,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    val version: String? = null,
    val baseModelId: String? = null
) {
    val modelId: String
        get() = name.removePrefix("models/")
}

interface VisionAnalyzer {
    val rateLimitState: StateFlow<RateLimitState>

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
     * Discovers available models supporting generateContent from Gemini API with full pagination.
     */
    suspend fun discoverModels(): Result<List<GeminiModel>>
}

