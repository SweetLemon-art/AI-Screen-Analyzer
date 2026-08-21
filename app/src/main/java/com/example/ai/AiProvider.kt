package com.example.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.image.ImageProcessor
import com.example.localai.LocalAiEvent
import com.example.localai.LocalAiProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Application-facing AI provider contract.
 * Callers depend on this abstraction rather than Gemini or LiteRT-LM directly.
 */
interface AiProvider {
    val type: AiProviderType

    suspend fun analyze(
        bitmap: Bitmap,
        context: AnalysisContext,
        settings: CaptureSettings = CaptureSettings.DEFAULT,
        userPrompt: String? = null
    ): AnalysisResult
}

enum class AiProviderType {
    GEMINI,
    LOCAL
}

/** Adapts the existing Gemini VisionAnalyzer to the unified provider contract. */
class GeminiAiProvider(
    private val delegate: VisionAnalyzer
) : AiProvider {
    override val type: AiProviderType = AiProviderType.GEMINI

    override suspend fun analyze(
        bitmap: Bitmap,
        context: AnalysisContext,
        settings: CaptureSettings,
        userPrompt: String?
    ): AnalysisResult {
        val effectiveContext = context.withUserPrompt(userPrompt)
        return delegate.analyze(bitmap, effectiveContext, settings)
    }
}

/**
 * Adapts the Local AI runtime to screen-analysis semantics.
 * The selected local model must advertise image capability before inference.
 */
class LocalAiScreenProvider(
    private val delegate: LocalAiProvider,
    private val generationTimeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS
) : AiProvider {
    override val type: AiProviderType = AiProviderType.LOCAL

    override suspend fun analyze(
        bitmap: Bitmap,
        context: AnalysisContext,
        settings: CaptureSettings,
        userPrompt: String?
    ): AnalysisResult {
        val startTime = System.currentTimeMillis()
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return failure(context, startTime, "Invalid analysis bitmap")
        }

        return try {
            val base64 = ImageProcessor.processForGeminiBase64(
                rawBitmap = bitmap,
                maxDimension = settings.maxResolutionDimension,
                quality = settings.compressionQuality
            )
            val imageBytes = Base64.decode(base64, Base64.DEFAULT)
            val effectiveContext = context.withUserPrompt(userPrompt)
            val prompt = buildPrompt(effectiveContext)
            var rawResponse = ""
            var failure: Throwable? = null

            val completed = withTimeoutOrNull(generationTimeoutMs) {
                delegate.generate(prompt, imageBytes).collect { event ->
                    when (event) {
                        LocalAiEvent.Started -> Unit
                        is LocalAiEvent.Token -> rawResponse += event.text
                        LocalAiEvent.Completed -> Unit
                        is LocalAiEvent.Failed -> failure = event.error
                    }
                }
                true
            } ?: false

            if (failure != null) {
                return failure(context, startTime, failure!!.message ?: "Local AI generation failed")
            }
            if (!completed) {
                return failure(context, startTime, "Local AI generation timed out")
            }

            parseResponse(context, startTime, rawResponse)
        } catch (error: Exception) {
            failure(context, startTime, error.message ?: "Local AI analysis failed")
        }
    }

    private fun buildPrompt(context: AnalysisContext): String = buildString {
        appendLine("You are an expert multimodal Android screen analyzer.")
        appendLine("Context Name: ${context.name}")
        appendLine("User Instructions: ${context.instructions}")
        appendLine("Target Response Language: ${context.language}")
        appendLine()
        appendLine("Analyze the supplied screen image strictly according to the user instructions.")
        appendLine("Return ONLY a JSON object with these fields:")
        appendLine("summary: concise string")
        appendLine("observations: array of concise strings")
        appendLine("conclusion: concise actionable takeaway")
    }

    private fun parseResponse(
        context: AnalysisContext,
        startTime: Long,
        rawResponse: String
    ): AnalysisResult {
        val cleaned = rawResponse.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val json = JSONObject(cleaned)
            val observations = json.optJSONArray("observations")?.toStringList().orEmpty()
            AnalysisResult(
                contextName = context.name,
                summary = json.optString("summary").ifBlank { cleaned },
                observations = observations,
                conclusion = json.optString("conclusion"),
                rawResponse = rawResponse,
                isSuccess = true,
                processingDurationMs = System.currentTimeMillis() - startTime
            )
        } catch (_: Exception) {
            AnalysisResult(
                contextName = context.name,
                summary = cleaned.ifBlank { "Local AI returned an empty response." },
                observations = emptyList(),
                conclusion = "",
                rawResponse = rawResponse,
                isSuccess = cleaned.isNotBlank(),
                errorMessage = if (cleaned.isBlank()) "Local AI returned an empty response." else null,
                processingDurationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun failure(context: AnalysisContext, startTime: Long, message: String): AnalysisResult =
        AnalysisResult(
            contextName = context.name,
            summary = "LOCAL_AI_ERROR",
            observations = listOf(message),
            conclusion = "Local AI analysis could not be completed.",
            rawResponse = "",
            isSuccess = false,
            errorMessage = message,
            processingDurationMs = System.currentTimeMillis() - startTime
        )

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private companion object {
        const val DEFAULT_GENERATION_TIMEOUT_MS = 120_000L
    }
}

/** Routes screen analysis to exactly one selected provider. */
class AiProviderRouter(
    providers: List<AiProvider>,
    initialProvider: AiProviderType = AiProviderType.GEMINI
) {
    private val providersByType = providers.associateBy { it.type }
    private var selected: AiProviderType = initialProvider

    init {
        require(providersByType.isNotEmpty()) { "At least one AI provider is required" }
        require(providersByType.containsKey(initialProvider)) {
            "Initial AI provider is not registered: $initialProvider"
        }
    }

    fun select(type: AiProviderType): Result<Unit> {
        if (!providersByType.containsKey(type)) {
            return Result.failure(IllegalArgumentException("AI provider is not registered: $type"))
        }
        selected = type
        return Result.success(Unit)
    }

    fun selectedType(): AiProviderType = selected

    suspend fun analyze(
        bitmap: Bitmap,
        context: AnalysisContext,
        settings: CaptureSettings = CaptureSettings.DEFAULT,
        userPrompt: String? = null
    ): AnalysisResult = providersByType.getValue(selected).analyze(bitmap, context, settings, userPrompt)
}

private fun AnalysisContext.withUserPrompt(userPrompt: String?): AnalysisContext {
    val prompt = userPrompt?.trim().orEmpty()
    if (prompt.isBlank()) return this
    val separator = if (instructions.isBlank()) "" else "\n\n"
    return copy(instructions = "$instructions${separator}User question: $prompt")
}
