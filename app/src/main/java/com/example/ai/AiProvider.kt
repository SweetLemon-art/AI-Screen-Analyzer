package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings

/**
 * Application-facing AI provider contract.
 * Provider implementations may be cloud or on-device; callers do not depend
 * on Gemini, LiteRT-LM, or any other concrete inference SDK.
 */
interface AiProvider {
    val type: AiProviderType

    suspend fun analyze(
        bitmap: Bitmap,
        context: AnalysisContext,
        settings: CaptureSettings = CaptureSettings.DEFAULT
    ): AnalysisResult
}

enum class AiProviderType {
    GEMINI,
    LOCAL
}
