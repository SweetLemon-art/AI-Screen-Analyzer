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
    private val modelProvider: () -> String = { "gemini-2.5-flash" },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com"
) : VisionAnalyzer {

    private val _quotaInfo = MutableStateFlow(
        GeminiQuotaInfo(
            status = if (apiKeyStore.hasApiKey()) "Connected" else "Not Configured",
            quota = "Unknown",
            rateLimit = "Normal",
            lastQuotaError = "None"
        )
    )
    override val quotaInfo: StateFlow<GeminiQuotaInfo> = _quotaInfo.asStateFlow()

    private fun getCleanBaseUrl(): String = baseUrl.trimEnd('/')

    private fun getGenerateContentUrl(): String {
        val rawModel = modelProvider().trim().ifBlank { "gemini-2.5-flash" }
        val cleanModel = rawModel.removePrefix("models/")
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
            _quotaInfo.value = _quotaInfo.value.copy(status = "Not Configured", quota = "Unknown")
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
                    .url(getGenerateContentUrl())
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
                    _quotaInfo.value = _quotaInfo.value.copy(
                        status = "Connected",
                        rateLimit = "Normal",
                        quota = "Available",
                        lastQuotaError = "None",
                        retryAfterSeconds = null
                    )

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

                // Handle HTTP 429 Quota / Rate-limit with bounded backoff
                if (statusCode == 429) {
                    val retryAfterSeconds = retryAfterHeader?.toIntOrNull()?.coerceIn(1, 30)
                        ?: (2.0.pow(attempt.toDouble()).toLong().coerceIn(2L, 30L)).toInt()

                    _quotaInfo.value = _quotaInfo.value.copy(
                        status = "Connected",
                        rateLimit = "Limited",
                        quota = "Limited",
                        lastQuotaError = "HTTP 429: Gemini API quota or rate limit reached.",
                        retryAfterSeconds = retryAfterSeconds
                    )

                    if (attempt < maxRetries) {
                        attempt++
                        // Delay must be cancellation-aware
                        delay(retryAfterSeconds * 1000L)
                        continue
                    } else {
                        // Max retries exceeded
                        val duration = System.currentTimeMillis() - startTime
                        val userErrorMessage = if (retryAfterHeader != null) {
                            "Gemini quota/rate limit reached. Try again in $retryAfterSeconds seconds."
                        } else {
                            "Gemini API quota or rate limit reached."
                        }
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

                if (statusCode in 401..403) {
                    _quotaInfo.value = _quotaInfo.value.copy(
                        status = "Error",
                        lastQuotaError = "Authentication / Authorization error ($statusCode)"
                    )
                }

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
                errorMessage = "Gemini API quota or rate limit reached.",
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
            _quotaInfo.value = GeminiQuotaInfo(status = "Not Configured", quota = "Unknown", rateLimit = "Normal")
            return@withContext ConnectionTestResult.Error("API key is not configured. Please enter a valid Gemini API key.")
        }

        try {
            val request = Request.Builder()
                .url(getListModelsUrl())
                .addHeader("x-goog-api-key", apiKey)
                .get()
                .build()

            val (statusCode, responseBodyString, isSuccess) = executeCancellationAwareCall(request).use { response ->
                Triple(response.code, response.body?.string().orEmpty(), response.isSuccessful)
            }

            if (isSuccess) {
                val models = parseModelsList(responseBodyString)
                _quotaInfo.value = GeminiQuotaInfo(
                    status = "Connected",
                    quota = "Available",
                    rateLimit = "Normal",
                    lastQuotaError = "None",
                    retryAfterSeconds = null
                )
                ConnectionTestResult.Success(
                    message = "Connection successful! Discovered ${models.size} available Gemini model(s).",
                    models = models
                )
            } else {
                if (statusCode == 429) {
                    _quotaInfo.value = _quotaInfo.value.copy(
                        status = "Connected",
                        rateLimit = "Limited",
                        quota = "Limited",
                        lastQuotaError = "HTTP 429: Gemini API quota or rate limit reached."
                    )
                } else if (statusCode in 401..403) {
                    _quotaInfo.value = _quotaInfo.value.copy(
                        status = "Error",
                        lastQuotaError = "Invalid or unauthorized API key"
                    )
                }
                val safeError = sanitizeHttpError(statusCode, responseBodyString)
                ConnectionTestResult.Error(safeError)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeError = when (e) {
                is java.net.UnknownHostException -> "Network error: Unable to resolve Gemini server. Check your internet connection."
                is java.net.SocketTimeoutException -> "Network timeout while reaching Gemini server."
                else -> "Connection test failed: ${e.localizedMessage ?: "Check network connection."}"
            }
            ConnectionTestResult.Error(safeError)
        }
    }

    /**
     * Discovers available models from Gemini API that support generateContent.
     */
    override suspend fun discoverModels(): Result<List<GeminiModel>> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("API key missing. Configure your Gemini API key in Settings."))
        }

        try {
            val request = Request.Builder()
                .url(getListModelsUrl())
                .addHeader("x-goog-api-key", apiKey)
                .get()
                .build()

            val (statusCode, responseBodyString, isSuccess) = executeCancellationAwareCall(request).use { response ->
                Triple(response.code, response.body?.string().orEmpty(), response.isSuccessful)
            }

            if (isSuccess) {
                val models = parseModelsList(responseBodyString)
                Result.success(models)
            } else {
                val safeError = sanitizeHttpError(statusCode, responseBodyString)
                Result.failure(RuntimeException(safeError))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
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

    fun parseModelsList(jsonString: String): List<GeminiModel> {
        val result = mutableListOf<GeminiModel>()
        try {
            val root = JSONObject(jsonString)
            val modelsArray = root.optJSONArray("models") ?: return emptyList()
            for (i in 0 until modelsArray.length()) {
                val modelObj = modelsArray.getJSONObject(i)
                val name = modelObj.optString("name", "")
                val displayName = modelObj.optString("displayName", name.removePrefix("models/"))
                val description = modelObj.optString("description", "")
                val methodsArray = modelObj.optJSONArray("supportedGenerationMethods")
                val methods = mutableListOf<String>()
                if (methodsArray != null) {
                    for (j in 0 until methodsArray.length()) {
                        methods.add(methodsArray.optString(j))
                    }
                }

                // Filter for models supporting generateContent
                if (methods.contains("generateContent")) {
                    result.add(
                        GeminiModel(
                            name = name,
                            displayName = displayName,
                            description = description,
                            supportedGenerationMethods = methods
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Return collected models on parse issue
        }
        return result
    }

    private fun sanitizeHttpError(statusCode: Int, responseBody: String): String {
        return when (statusCode) {
            400 -> "Invalid request parameters."
            401, 403 -> "API key is invalid or unauthorized."
            404 -> "Gemini API endpoint or configuration was not found."
            408 -> "Request timeout from server. Please try again."
            429 -> "Gemini API quota or rate limit reached."
            500, 502, 503, 504 -> "Gemini service temporarily unavailable."
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
