package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext

interface VisionAnalyzer {
    /**
     * Sends the captured screen bitmap together with the user-defined analysis context
     * to the multimodal AI model.
     *
     * @param bitmap Screen capture image
     * @param context Instructions, objective, and language requested by the user
     * @return Formatted [AnalysisResult]
     */
    suspend fun analyze(bitmap: Bitmap, context: AnalysisContext): AnalysisResult
}
