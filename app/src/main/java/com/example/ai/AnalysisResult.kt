package com.example.ai

/**
 * Result of an AI visual analysis on a captured screen frame.
 * Extensible and generic (no hardcoded domain specifics).
 */
data class AnalysisResult(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val contextName: String,
    val summary: String,
    val observations: List<String> = emptyList(),
    val conclusion: String = "",
    val rawResponse: String = "",
    val isSuccess: Boolean = true,
    val errorMessage: String? = null,
    val processingDurationMs: Long = 0L
)
