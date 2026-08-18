package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings

sealed class ConnectionTestResult {
    data class Success(val message: String) : ConnectionTestResult()
    data class Error(val message: String) : ConnectionTestResult()
}

interface VisionAnalyzer {
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
     * Verifies the configured API key with a lightweight prompt.
     */
    suspend fun testConnection(): ConnectionTestResult
}
