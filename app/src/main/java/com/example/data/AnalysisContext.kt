package com.example.data

/**
 * Defines the user's intent and guidelines for what Gemini should analyze on screen.
 * The application is fully generic; any context can be provided.
 */
data class AnalysisContext(
    val id: String,
    val name: String,
    val instructions: String,
    val language: String = "English",
    val isPreset: Boolean = false
) {
    companion object {
        val DEFAULT_PRESETS = listOf(
            AnalysisContext(
                id = "general_explainer",
                name = "Screen Explainer",
                instructions = "Analyze what is visible on screen. Summarize the main content, key highlights, and context. Identify important text, visual elements, and status indicators.",
                language = "English",
                isPreset = true
            ),
            AnalysisContext(
                id = "chart_analysis",
                name = "Chart & Market Analysis",
                instructions = "Analyze the visible chart or graph. Focus on overall trend, support and resistance levels, momentum, candlestick patterns or anomalies, and key data points.",
                language = "English",
                isPreset = true
            ),
            AnalysisContext(
                id = "dashboard_reader",
                name = "Dashboard & Metrics",
                instructions = "Read the visible dashboard, analytics, or stats. Extract critical metrics, active KPIs, outliers, changes, and operational status.",
                language = "English",
                isPreset = true
            ),
            AnalysisContext(
                id = "document_extractor",
                name = "Document & Text Extractor",
                instructions = "Read and parse the document or article visible on screen. Provide key takeaways, essential facts, action items, or critical details.",
                language = "English",
                isPreset = true
            ),
            AnalysisContext(
                id = "ui_bug_inspector",
                name = "UI/UX & Visual QA",
                instructions = "Inspect the visible user interface. Identify layout inconsistencies, visual glitches, contrast issues, text truncations, or UX friction points.",
                language = "English",
                isPreset = true
            )
        )

        val DEFAULT = DEFAULT_PRESETS.first()
    }
}
