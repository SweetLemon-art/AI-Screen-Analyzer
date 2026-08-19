package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.image.ImageProcessor
import com.example.security.GeminiApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow

class GeminiVisionAnalyzer(
    private val apiKeyStore: GeminiApiKeyStore,
    private val modelProvider: () -> String? = { null },
    private val compatibleModelsProvider: () -> List<GeminiModel> = { emptyList() },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com"
) : VisionAnalyzer {

    private val _rateLimitState = MutableStateFlow(RateLimitState.UNKNOWN)
    override val rateLimitState: StateFlow<RateLimitState> = _rateLimitState.asStateFlow()

    private fun getCleanBaseUrl(): String = baseUrl.trimEnd('/')

    private fun getGenerateContentUrl(modelId: String): String {
        val cleanModel = normalizeModelId(modelId)
        return "${getCleanBaseUrl()}/v1beta/models/$cleanModel:generateContent"
    }

    private fun getListModelsUrl(): String {
        return "${getCleanBaseUrl()}/v1beta/models"
    }

    override suspend fun analyze(
        bitmap: Bitmap,
        context: AnalysisContext,
        settings: CaptureSettings
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val apiKey = apiKeyStore.getApiKey()

        if (apiKey.isNullOrBlank()) {
            val duration = System.currentTimeMillis() - startTime
            return@withContext AnalysisResult(
                contextName = context.name,
                summary = "API Key not configured",
                observations = listOf(
                    "No Gemini API key is currently configured.",
                    "Please navigate to Settings and enter your Gemini API key."
                ),
                conclusion = "Enter your Gemini API key in Settings to enable live analysis.",
                rawResponse = "",
                isSuccess = false,
                errorMessage = "API key missing. Configure your Gemini API key in Settings.",
                processingDurationMs = duration
            )
        }

        val rawSelectedModel = modelProvider()
        val selectedModelId = normalizeModelId(rawSelectedModel)
        if (selectedModelId.isBlank()) {
            val duration = System.currentTimeMillis() - startTime
            return@withContext AnalysisResult(
                contextName = context.name,
                summary = "NO_MODEL_SELECTED",
                observations = listOf(
                    "No model has been selected for Gemini analysis.",
                    "Please select a discovered Gemini model in Settings."
                ),
                conclusion = "Select a model in Settings to begin screen analysis.",
                rawResponse = "",
                isSuccess = false,
                errorMessage = "NO_MODEL_SELECTED: No Gemini model selected.",
                processingDurationMs = duration
            )
        }

        // Generation safety guard: Validate against latest compatible models list
        val compatibleList = compatibleModelsProvider()
        val matchingModel = compatibleList.find { normalizeModelId(it.name) == selectedModelId }

        if (matchingModel == null) {
            val duration = System.currentTimeMillis() - startTime
            return@withContext AnalysisResult(
                contextName = context.name,
                summary = "MODEL_NOT_AVAILABLE",
                observations = listOf(
                    "Selected model '$selectedModelId' is not present in the latest compatible model list.",
                    "Please refresh model discovery in Settings."
                ),
                conclusion = "Selected model is unavailable.",
                rawResponse = "",
                isSuccess = false,
                errorMessage = "MODEL_NOT_AVAILABLE: Selected model '$selectedModelId' is not available in discovered compatible models.",
                processingDurationMs = duration
            )
        }

        if (!matchingModel.supportedGenerationMethods.contains("generateContent")) {
            val duration = System.currentTimeMillis() - startTime
            return@withContext AnalysisResult(
                contextName = context.name,
                summary = "MODEL_NOT_AVAILABLE",
                observations = listOf(
                    "Selected model '$selectedModelId' does not support generateContent."
                ),
                conclusion = "Model does not support content generation.",
                rawResponse = "",
                isSuccess = false,
                errorMessage = "MODEL_NOT_AVAILABLE: Model '$selectedModelId' does not support generateContent.",
                processingDurationMs = duration
            )
        }

        if (matchingModel.imageInputCapability != ImageInputCapability.SUPPORTED) {
            val duration = System.currentTimeMillis() - startTime
            return@withContext AnalysisResult(
                contextName = context.name,
                summary = "MODEL_NOT_IMAGE_CAPABLE",
                observations = listOf(
                    "Selected model '$selectedModelId' has not been verified to support image input.",
                    "Image capability state is UNKNOWN or unsupported."
                ),
                conclusion = "Model cannot be used for visual screen analysis.",
                rawResponse = "",
                isSuccess = false,
                errorMessage = "MODEL_NOT_IMAGE_CAPABLE: Selected model '$selectedModelId' does not support image input.",
                processingDurationMs = duration
            )
        }

        try {
            // Process and encode image to Base64 (temporary scaled bitmaps are recycled inside processForGeminiBase64)
            val base64Image = ImageProcessor.processForGeminiBase64(
                rawBitmap = bitmap,
                maxDimension = settings.maxResolutionDimension,
                quality = settings.compressionQuality
            )

            val promptText = buildString {
                appendLine("You are an expert multimodal visual screen analyzer.")
                appendLine("Context Name: ${context.name}")
                appendLine("User Instructions: ${context.instructions}")
                appendLine("Target Response Language: ${context.language}")
                appendLine()
                appendLine("Carefully inspect the provided Android screen capture. Focus strictly on the user instructions above.")
                appendLine("Respond with a structured JSON object conforming to the schema:")
                appendLine("- summary: Concise 1-2 sentence summary of what is happening on screen relevant to user instructions.")
                appendLine("- observations: A list of key visual findings / points.")
                appendLine("- conclusion: Main takeaway, status, or actionable insight.")
            }

            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray()
                val contentObj = JSONObject()
                val partsArray = JSONArray()

                // Text prompt part
                partsArray.put(JSONObject().put("text", promptText))

                // Inline image part
                val imagePart = JSONObject().apply {
                    val inlineData = JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Image)
                    }
                    put("inlineData", inlineData)
                }
                partsArray.put(imagePart)

                contentObj.put("parts", partsArray)
                contentsArray.put(contentObj)
                put("contents", contentsArray)

                // Structured JSON response schema
                val responseSchema = JSONObject().apply {
                    put("type", "OBJECT")
                    val properties = JSONObject().apply {
                        put("summary", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "A concise summary of what is visible")
                        })
                        put("observations", JSONObject().apply {
                            put("type", "ARRAY")
                            put("items", JSONObject().apply { put("type", "STRING") })
                            put("description", "Key visual points observed")
                        })
                        put("conclusion", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Actionable takeaway or status")
                        })
                    }
                    put("properties", properties)
                    put("required", JSONArray().put("summary").put("observations").put("conclusion"))
                }

                val generationConfig = JSONObject().apply {
                    put("response_mime_type", "application/json")
                    put("response_schema", responseSchema)
                    put("temperature", 0.2)
                }
                put("generationConfig", generationConfig)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            val maxRetries = 3
            var attempt = 0

            while (attempt <= maxRetries) {
                val request = Request.Builder()
                    .url(getGenerateContentUrl(selectedModelId))
                    .addHeader("x-goog-api-key", apiKey)
                    .post(requestBody)
                    .build()

                // Execute network call with guaranteed Response.use { ... } cleanup
                val (statusCode, responseBodyString, isSuccess, retryAfterHeader) = executeCancellationAwareCall(request).use { response ->
                    ResponseSummary(
                        statusCode = response.code,
                        bodyString = response.body?.string().orEmpty(),
                        isSuccess = response.isSuccessful,
                        retryAfter = response.header("Retry-After")
                    )
                }

                if (isSuccess) {
                    _rateLimitState.value = RateLimitState.NORMAL

                    val duration = System.currentTimeMillis() - startTime
                    val rawText = parseGeminiResponseContent(responseBodyString)
                    val (summary, observations, conclusion) = parseStructuredResponse(rawText)

                    return@withContext AnalysisResult(
                        contextName = context.name,
                        summary = summary.ifBlank { "Screen analyzed successfully." },
                        observations = observations,
                        conclusion = conclusion,
                        rawResponse = rawText,
                        isSuccess = true,
                        errorMessage = null,
                        processingDurationMs = duration
                    )
                }

                // Handle HTTP 429 Rate-limit with bounded backoff
                if (statusCode == 429) {
                    _rateLimitState.value = RateLimitState.RATE_LIMITED
                    val retryAfterSeconds = retryAfterHeader?.toIntOrNull()?.coerceIn(1, 30)
                        ?: (2.0.pow(attempt.toDouble()).toLong().coerceIn(2L, 30L)).toInt()

                    if (attempt < maxRetries) {
                        attempt++
                        // Delay must be cancellation-aware
                        delay(retryAfterSeconds * 1000L)
                        continue
                    } else {
                        // Max retries exceeded
                        val duration = System.currentTimeMillis() - startTime
                        val userErrorMessage = "Gemini API rate limit reached."
                        return@withContext AnalysisResult(
                            contextName = context.name,
                            summary = "Rate limit reached (429)",
                            observations = listOf(userErrorMessage),
                            conclusion = "Wait for rate limit window or increase delay in Settings.",
                            rawResponse = "",
                            isSuccess = false,
                            errorMessage = userErrorMessage,
                            processingDurationMs = duration
                        )
                    }
                }

                // Non-429 error response (e.g. 401, 403, 404, 5xx)
                val duration = System.currentTimeMillis() - startTime
                val userErrorMessage = sanitizeHttpError(statusCode, responseBodyString)

                return@withContext AnalysisResult(
                    contextName = context.name,
                    summary = "Analysis request failed ($statusCode)",
                    observations = listOf(userErrorMessage),
                    conclusion = "Check your API key, model selection, and network connectivity.",
                    rawResponse = "",
                    isSuccess = false,
                    errorMessage = userErrorMessage,
                    processingDurationMs = duration
                )
            }

            val duration = System.currentTimeMillis() - startTime
            AnalysisResult(
                contextName = context.name,
                summary = "Analysis failed",
                observations = listOf("Max retry attempts exceeded."),
                conclusion = "Please try again later.",
                rawResponse = "",
                isSuccess = false,
                errorMessage = "Gemini API rate limit reached.",
                processingDurationMs = duration
            )
        } catch (e: CancellationException) {
            // MUST propagate cancellation promptly
            throw e
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            val safeMessage = when (e) {
                is java.net.UnknownHostException -> "No internet connection. Please verify your network."
                is java.net.SocketTimeoutException -> "Request timed out while waiting for Gemini response."
                else -> "Network communication error: ${e.localizedMessage ?: "Please try again."}"
            }
            AnalysisResult(
                contextName = context.name,
                summary = "Failed to complete AI screen analysis",
                observations = listOf(safeMessage),
                conclusion = "Please verify network connectivity and try again.",
                rawResponse = "",
                isSuccess = false,
                errorMessage = safeMessage,
                processingDurationMs = duration
            )
        }
    }

    /**
     * Verifies the configured API key using models.list dynamic model discovery.
     */
    override suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext ConnectionTestResult.Error("API key is not configured. Please enter a valid Gemini API key.")
        }

        val discoveryResult = discoverModels()
        discoveryResult.fold(
            onSuccess = { models ->
                ConnectionTestResult.Success(
                    message = "Connection successful! Discovered ${models.size} available Gemini model(s).",
                    models = models
                )
            },
            onFailure = { error ->
                val errorMessage = error.localizedMessage ?: "Connection test failed."
                ConnectionTestResult.Error(errorMessage)
            }
        )
    }

    /**
     * Discovers available models from Gemini API that support generateContent with full pagination.
     */
    override suspend fun discoverModels(): Result<List<GeminiModel>> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("API key missing. Configure your Gemini API key in Settings."))
        }

        val allModels = mutableListOf<GeminiModel>()
        val seenPageTokens = mutableSetOf<String>()
        var nextPageToken: String? = null

        try {
            do {
                val url = if (nextPageToken.isNullOrBlank()) {
                    getListModelsUrl()
                } else {
                    "${getListModelsUrl()}?pageToken=$nextPageToken"
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .get()
                    .build()

                val (statusCode, responseBodyString, isSuccess) = executeCancellationAwareCall(request).use { response ->
                    Triple(response.code, response.body?.string().orEmpty(), response.isSuccessful)
                }

                if (!isSuccess) {
                    if (statusCode == 429) {
                        _rateLimitState.value = RateLimitState.RATE_LIMITED
                    }
                    val safeError = sanitizeHttpError(statusCode, responseBodyString)
                    return@withContext Result.failure(RuntimeException(safeError))
                }

                _rateLimitState.value = RateLimitState.NORMAL

                val page = parseModelsPage(responseBodyString)
                allModels.addAll(page.models)

                val token = page.nextPageToken
                if (token.isNullOrBlank() || seenPageTokens.contains(token)) {
                    nextPageToken = null
                } else {
                    seenPageTokens.add(token)
                    nextPageToken = token
                }
            } while (nextPageToken != null)

            Result.success(allModels)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            val safeError = when (e) {
                is java.net.UnknownHostException -> "Network error: Unable to resolve Gemini server. Check your internet connection."
                is java.net.SocketTimeoutException -> "Network timeout while reaching Gemini server."
                else -> "Connection test failed: ${e.localizedMessage ?: "Check network connection."}"
            }
            Result.failure(RuntimeException(safeError, e))
        }
    }

    private suspend fun executeCancellationAwareCall(request: Request): Response {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }
            })
        }
    }

    data class ModelsPage(
        val models: List<GeminiModel>,
        val nextPageToken: String?
    )

    fun parseModelsPage(jsonString: String): ModelsPage {
        val root = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            throw IllegalArgumentException("MALFORMED_MODEL_RESPONSE: Invalid JSON format", e)
        }

        if (!root.has("models")) {
            throw IllegalArgumentException("MALFORMED_MODEL_RESPONSE: Missing 'models' property in response")
        }

        val modelsArray = root.optJSONArray("models")
            ?: throw IllegalArgumentException("MALFORMED_MODEL_RESPONSE: 'models' must be an array")

        val modelsList = mutableListOf<GeminiModel>()

        for (i in 0 until modelsArray.length()) {
            val modelObj = modelsArray.optJSONObject(i) ?: continue
            val name = modelObj.optString("name", "").trim()
            if (name.isBlank()) continue

            val modelId = normalizeModelId(name)
            val displayName = modelObj.optString("displayName", modelId).ifBlank { modelId }
            val description = modelObj.optString("description", "")
            val methodsArray = modelObj.optJSONArray("supportedGenerationMethods")
            val methods = mutableListOf<String>()
            if (methodsArray != null) {
                for (j in 0 until methodsArray.length()) {
                    val method = methodsArray.optString(j)
                    if (method.isNotBlank()) {
                        methods.add(method)
                    }
                }
            }

            // Image input capability strategy (No guessing / No heuristics):
            // We inspect only actual input modality metadata returned by the API endpoint.
            // If the API returns supportedInputModalities or inputModalities containing "IMAGE",
            // we mark it as SUPPORTED. Otherwise, it remains UNKNOWN.
            // Model names, prefixes, or generateContent alone do NOT imply image capability.
            val modalitiesArray = modelObj.optJSONArray("supportedInputModalities")
                ?: modelObj.optJSONArray("inputModalities")
                ?: modelObj.optJSONArray("supportedModalities")

            var capability = ImageInputCapability.UNKNOWN
            if (modalitiesArray != null) {
                for (j in 0 until modalitiesArray.length()) {
                    val modality = modalitiesArray.optString(j, "").trim().uppercase()
                    if (modality == "IMAGE" || modality == "IMAGE/JPEG" || modality == "IMAGE/PNG" || modality == "IMAGE/WEBP" || modality == "MODALITY_IMAGE") {
                        capability = ImageInputCapability.SUPPORTED
                        break
                    }
                }
            }

            val inputTokenLimit = if (modelObj.has("inputTokenLimit")) modelObj.optInt("inputTokenLimit") else null
            val outputTokenLimit = if (modelObj.has("outputTokenLimit")) modelObj.optInt("outputTokenLimit") else null
            val version = if (modelObj.has("version")) modelObj.optString("version", null) else null
            val baseModelId = if (modelObj.has("baseModelId")) modelObj.optString("baseModelId", null) else null

            modelsList.add(
                GeminiModel(
                    name = name,
                    displayName = displayName,
                    description = description,
                    supportedGenerationMethods = methods,
                    inputTokenLimit = inputTokenLimit,
                    outputTokenLimit = outputTokenLimit,
                    version = version,
                    baseModelId = baseModelId,
                    imageInputCapability = capability
                )
            )
        }

        val nextPageToken = root.optString("nextPageToken", "").trim().ifBlank { null }
        return ModelsPage(models = modelsList, nextPageToken = nextPageToken)
    }

    fun parseModelsList(jsonString: String): List<GeminiModel> {
        return parseModelsPage(jsonString).models
    }

    private fun sanitizeHttpError(statusCode: Int, responseBody: String): String {
        return when (statusCode) {
            400 -> "Invalid request parameters."
            401 -> "API key is invalid."
            403 -> "API key is unauthorized or permission denied."
            404 -> "Gemini endpoint or model was not found."
            408 -> "Request timeout from server. Please try again."
            429 -> "Gemini API rate limit reached."
            in 500..599 -> "Gemini service temporarily unavailable."
            else -> "HTTP $statusCode: Unexpected API error."
        }
    }

    private fun parseGeminiResponseContent(jsonString: String): String {
        return try {
            val root = JSONObject(jsonString)
            val candidates = root.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val text = parts.getJSONObject(i).optString("text", "")
                        sb.append(text)
                    }
                    return sb.toString().trim()
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseStructuredResponse(rawContent: String): Triple<String, List<String>, String> {
        if (rawContent.isBlank()) {
            return Triple("No response content returned by AI.", emptyList(), "")
        }

        try {
            val cleanJson = rawContent.trim()
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val json = JSONObject(cleanJson)
            val summary = json.optString("summary", "").trim()
            val observationsList = mutableListOf<String>()
            val observationsArray = json.optJSONArray("observations")
            if (observationsArray != null) {
                for (i in 0 until observationsArray.length()) {
                    val obs = observationsArray.optString(i, "").trim()
                    if (obs.isNotEmpty()) {
                        observationsList.add(obs)
                    }
                }
            }
            val conclusion = json.optString("conclusion", "").trim()

            if (summary.isNotEmpty() || observationsList.isNotEmpty() || conclusion.isNotEmpty()) {
                return Triple(summary, observationsList, conclusion)
            }
        } catch (ignored: Exception) {
            // Fall back to line parser
        }

        val lines = rawContent.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val summaryBuffer = StringBuilder()
        val observations = mutableListOf<String>()
        val conclusionBuffer = StringBuilder()
        var currentSection = ""

        for (line in lines) {
            val upper = line.uppercase()
            when {
                upper.startsWith("SUMMARY:") -> {
                    currentSection = "summary"
                    val content = line.substringAfter("SUMMARY:", "").trim().removePrefix("**").removeSuffix("**").trim()
                    if (content.isNotEmpty()) summaryBuffer.append(content).append(" ")
                }
                upper.startsWith("OBSERVATIONS:") || upper.startsWith("OBSERVATION:") || upper.startsWith("FINDINGS:") -> {
                    currentSection = "observations"
                }
                upper.startsWith("CONCLUSION:") || upper.startsWith("TAKEAWAYS:") || upper.startsWith("TAKEAWAY:") -> {
                    currentSection = "conclusion"
                }
                else -> {
                    when (currentSection) {
                        "summary" -> summaryBuffer.append(line).append(" ")
                        "observations" -> {
                            val cleanLine = line.removePrefix("-").removePrefix("*").removePrefix("•").trim()
                            if (cleanLine.isNotEmpty()) observations.add(cleanLine)
                        }
                        "conclusion" -> conclusionBuffer.append(line).append(" ")
                        else -> {
                            if (line.startsWith("-") || line.startsWith("*") || line.startsWith("•")) {
                                observations.add(line.removePrefix("-").removePrefix("*").removePrefix("•").trim())
                            } else if (summaryBuffer.isEmpty()) {
                                summaryBuffer.append(line).append(" ")
                            }
                        }
                    }
                }
            }
        }

        val summary = summaryBuffer.toString().trim().ifEmpty {
            rawContent.take(200) + if (rawContent.length > 200) "..." else ""
        }
        val conclusion = conclusionBuffer.toString().trim()

        return Triple(summary, observations, conclusion)
    }

    private data class ResponseSummary(
        val statusCode: Int,
        val bodyString: String,
        val isSuccess: Boolean,
        val retryAfter: String?
    )
}
