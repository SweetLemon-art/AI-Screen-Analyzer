package com.example.ai

import android.graphics.Bitmap
import com.example.BuildConfig
import com.example.data.AnalysisContext
import com.example.image.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiVisionAnalyzer : VisionAnalyzer {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext): AnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val apiKey = BuildConfig.GEMINI_API_KEY.trim()

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey.contains("placeholder", ignoreCase = true)) {
            val duration = System.currentTimeMillis() - startTime
            return@withContext AnalysisResult(
                contextName = context.name,
                summary = "Gemini API key is not configured.",
                observations = listOf(
                    "Please configure your GEMINI_API_KEY in the Secrets panel in Google AI Studio.",
                    "Ensure .env or Secrets injection is active."
                ),
                conclusion = "Add a valid Gemini API key to enable live AI vision processing.",
                rawResponse = "Missing API Key: GEMINI_API_KEY is unset or placeholder.",
                isSuccess = false,
                errorMessage = "Missing or placeholder GEMINI_API_KEY. Please provide an API key via the Secrets panel.",
                processingDurationMs = duration
            )
        }

        try {
            // Process and downscale bitmap to optimize latency
            val (_, base64Image) = ImageProcessor.processForGemini(
                rawBitmap = bitmap,
                maxDimension = 1080,
                quality = 82
            )

            val promptText = buildString {
                appendLine("You are an expert real-time multimodal visual screen analyzer.")
                appendLine("Context Name: ${context.name}")
                appendLine("User Instructions: ${context.instructions}")
                appendLine("Target Language: ${context.language}")
                appendLine()
                appendLine("Carefully inspect the provided Android screen capture. Focus strictly on the user instructions above.")
                appendLine("Structure your answer in the specified target language (${context.language}) with:")
                appendLine("SUMMARY: (A 1-2 sentence direct summary of what is happening on screen relevant to the context)")
                appendLine("OBSERVATIONS:")
                appendLine("- (Key visual point 1)")
                appendLine("- (Key visual point 2)")
                appendLine("- (Key visual point 3)")
                appendLine("CONCLUSION: (Main insight, status, or actionable takeaway)")
            }

            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray()
                val contentObj = JSONObject()
                val partsArray = JSONArray()

                // Text prompt part
                val textPart = JSONObject().put("text", promptText)
                partsArray.put(textPart)

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

                val generationConfig = JSONObject().apply {
                    put("temperature", 0.2)
                    put("topP", 0.95)
                }
                put("generationConfig", generationConfig)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBodyString = response.body?.string() ?: ""
            val duration = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val errJson = JSONObject(responseBodyString)
                    errJson.optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}: ${response.message}"
                } catch (e: Exception) {
                    "HTTP ${response.code}: ${response.message}"
                }

                return@withContext AnalysisResult(
                    contextName = context.name,
                    summary = "Gemini API call failed (HTTP ${response.code}).",
                    observations = listOf("Error: $errorMsg"),
                    conclusion = "Check your network connection and API key quota.",
                    rawResponse = responseBodyString,
                    isSuccess = false,
                    errorMessage = errorMsg,
                    processingDurationMs = duration
                )
            }

            val parsedText = parseGeminiResponseText(responseBodyString)
            val (summary, observations, conclusion) = extractSections(parsedText)

            AnalysisResult(
                contextName = context.name,
                summary = summary.ifBlank { "Screen analyzed according to instructions." },
                observations = observations,
                conclusion = conclusion,
                rawResponse = parsedText,
                isSuccess = true,
                errorMessage = null,
                processingDurationMs = duration
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            AnalysisResult(
                contextName = context.name,
                summary = "Failed to complete AI screen analysis.",
                observations = listOf("Exception: ${e.localizedMessage ?: e.message}"),
                conclusion = "Please verify network connectivity and try again.",
                rawResponse = e.stackTraceToString(),
                isSuccess = false,
                errorMessage = e.localizedMessage ?: "Unknown network error",
                processingDurationMs = duration
            )
        }
    }

    private fun parseGeminiResponseText(jsonString: String): String {
        return try {
            val root = JSONObject(jsonString)
            val candidates = root.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val stringBuilder = StringBuilder()
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val text = part.optString("text", "")
                        stringBuilder.append(text)
                    }
                    return stringBuilder.toString().trim()
                }
            }
            "No text returned by AI."
        } catch (e: Exception) {
            "Unable to parse response: ${e.message}"
        }
    }

    private fun extractSections(rawText: String): Triple<String, List<String>, String> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var summary = ""
        val observations = mutableListOf<String>()
        var conclusion = ""

        var currentSection = "" // "summary", "observations", "conclusion"
        val summaryBuffer = StringBuilder()
        val conclusionBuffer = StringBuilder()

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
                    val content = line.substringAfter(":", "").trim().removePrefix("**").removeSuffix("**").trim()
                    if (content.isNotEmpty()) conclusionBuffer.append(content).append(" ")
                }
                else -> {
                    when (currentSection) {
                        "summary" -> summaryBuffer.append(line).append(" ")
                        "observations" -> {
                            val cleanLine = line.removePrefix("-").removePrefix("*").removePrefix("•").trim()
                            if (cleanLine.isNotEmpty()) {
                                observations.add(cleanLine)
                            }
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

        summary = summaryBuffer.toString().trim()
        conclusion = conclusionBuffer.toString().trim()

        if (summary.isEmpty() && rawText.isNotEmpty()) {
            summary = rawText.take(200) + if (rawText.length > 200) "..." else ""
        }

        return Triple(summary, observations, conclusion)
    }
}
