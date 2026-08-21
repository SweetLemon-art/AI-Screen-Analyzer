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

    private fun getListModelsUrl(): String = "${getCleanBaseUrl()}/v1beta/models"

    override suspend fun analyze(
        bitmap: Bitmap,
        context: AnalysisContext,
        settings: CaptureSettings
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey.isNullOrBlank()) return@withContext AnalysisResult(contextName=context.name, summary="API Key not configured", observations=listOf("No Gemini API key is currently configured.", "Please navigate to Settings and enter your Gemini API key."), conclusion="Enter your Gemini API key in Settings to enable live analysis.", rawResponse="", isSuccess=false, errorMessage="API key missing. Configure your Gemini API key in Settings.", processingDurationMs=System.currentTimeMillis()-startTime)
        val selectedModelId = normalizeModelId(modelProvider())
        if (selectedModelId.isBlank()) return@withContext AnalysisResult(contextName=context.name, summary="NO_MODEL_SELECTED", observations=listOf("No model has been selected for Gemini analysis.", "Please select a discovered Gemini model in Settings."), conclusion="Select a model in Settings to begin screen analysis.", rawResponse="", isSuccess=false, errorMessage="NO_MODEL_SELECTED: No Gemini model selected.", processingDurationMs=System.currentTimeMillis()-startTime)
        val matchingModel = compatibleModelsProvider().find { it.canonicalModelId == selectedModelId || it.modelId == selectedModelId || normalizeModelId(it.name) == selectedModelId }
        if (matchingModel == null) return@withContext AnalysisResult(contextName=context.name, summary="MODEL_NOT_AVAILABLE", observations=listOf("Selected model '$selectedModelId' is not present in the latest compatible model list.", "Please refresh model discovery in Settings."), conclusion="Selected model is unavailable.", rawResponse="", isSuccess=false, errorMessage="MODEL_NOT_AVAILABLE: Selected model '$selectedModelId' is not available in discovered compatible models.", processingDurationMs=System.currentTimeMillis()-startTime)
        if (!matchingModel.supportedGenerationMethods.contains("generateContent")) return@withContext AnalysisResult(contextName=context.name, summary="MODEL_NOT_AVAILABLE", observations=listOf("Selected model '$selectedModelId' does not support generateContent."), conclusion="Model does not support content generation.", rawResponse="", isSuccess=false, errorMessage="MODEL_NOT_AVAILABLE: Model '$selectedModelId' does not support generateContent.", processingDurationMs=System.currentTimeMillis()-startTime)
        val canonicalModelIdToUse = matchingModel.canonicalModelId
        try {
            val base64Image = ImageProcessor.processForGeminiBase64(bitmap, settings.maxResolutionDimension, settings.compressionQuality)
            val promptText = buildString {
                appendLine("You are an expert multimodal visual screen analyzer.")
                appendLine("Context Name: ${context.name}")
                appendLine("User Instructions: ${context.instructions}")
                appendLine("Target Response Language: ${context.language}")
                appendLine("Carefully inspect the provided Android screen capture. Focus strictly on the user instructions above.")
                appendLine("Respond with a structured JSON object conforming to the schema: summary (string), observations (array of strings), conclusion (string).")
            }
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", promptText)).put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", base64Image))))))
                put("generationConfig", JSONObject().apply {
                    put("response_mime_type", "application/json")
                    put("response_schema", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("summary", JSONObject().apply { put("type", "STRING") })
                            put("observations", JSONObject().apply { put("type", "ARRAY"); put("items", JSONObject().apply { put("type", "STRING") }) })
                            put("conclusion", JSONObject().apply { put("type", "STRING") })
                        })
                        put("required", JSONArray().put("summary").put("observations").put("conclusion"))
                    })
                    put("temperature", 0.2)
                })
            }
            val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val maxRetries = 3
            var attempt = 0
            while (attempt <= maxRetries) {
                val request = Request.Builder().url(getGenerateContentUrl(canonicalModelIdToUse)).addHeader("x-goog-api-key", apiKey).post(requestBody).build()
                val (statusCode, responseBodyString, isSuccess, retryAfterHeader) = executeCancellationAwareCall(request).use { response -> ResponseSummary(response.code, response.body?.string().orEmpty(), response.isSuccessful, response.header("Retry-After")) }
                if (isSuccess) {
                    _rateLimitState.value = RateLimitState.NORMAL
                    val rawText = parseGeminiResponseContent(responseBodyString)
                    val (summary, observations, conclusion) = parseStructuredResponse(rawText)
                    return@withContext AnalysisResult(contextName=context.name, summary=summary.ifBlank { "Screen analyzed successfully." }, observations=observations, conclusion=conclusion, rawResponse=rawText, isSuccess=true, errorMessage=null, processingDurationMs=System.currentTimeMillis()-startTime)
                }
                if (statusCode == 404) return@withContext AnalysisResult(contextName=context.name, summary="MODEL_NOT_FOUND", observations=listOf("Gemini endpoint or model was not found (404)."), conclusion="Selected model '$canonicalModelIdToUse' is not found. Please choose another model in Settings.", rawResponse="", isSuccess=false, errorMessage="MODEL_NOT_FOUND: Gemini endpoint or model was not found.", processingDurationMs=System.currentTimeMillis()-startTime)
                if (statusCode == 429 || statusCode in 500..599) {
                    if (statusCode == 429) _rateLimitState.value = RateLimitState.RATE_LIMITED
                    if (attempt < maxRetries) {
                        val retryAfterSeconds = retryAfterHeader?.toIntOrNull()?.coerceIn(1, 30) ?: 2.0.pow(attempt.toDouble()).toLong().coerceIn(2L, 30L).toInt()
                        attempt++
                        delay(retryAfterSeconds * 1000L)
                        continue
                    }
                    val message = if (statusCode == 429) "RATE_LIMITED: Gemini API rate limit reached." else sanitizeHttpError(statusCode, responseBodyString)
                    return@withContext AnalysisResult(contextName=context.name, summary=if (statusCode == 429) "RATE_LIMITED" else "Analysis request failed ($statusCode)", observations=listOf(message), conclusion="Please try again later.", rawResponse="", isSuccess=false, errorMessage=message, processingDurationMs=System.currentTimeMillis()-startTime)
                }
                val message = sanitizeHttpError(statusCode, responseBodyString)
                val summaryCode = when (statusCode) { 401 -> "INVALID_API_KEY"; 403 -> "UNAUTHORIZED"; else -> "Analysis request failed ($statusCode)" }
                return@withContext AnalysisResult(contextName=context.name, summary=summaryCode, observations=listOf(message), conclusion="Check your API key, model selection, and network connectivity.", rawResponse="", isSuccess=false, errorMessage=message, processingDurationMs=System.currentTimeMillis()-startTime)
            }
            AnalysisResult(contextName=context.name, summary="Analysis failed", observations=listOf("Max retry attempts exceeded."), conclusion="Please try again later.", rawResponse="", isSuccess=false, errorMessage="Gemini API rate limit reached.", processingDurationMs=System.currentTimeMillis()-startTime)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val safeMessage = when (e) { is java.net.UnknownHostException -> "No internet connection. Please verify your network."; is java.net.SocketTimeoutException -> "Request timed out while waiting for Gemini response."; else -> "Network communication error: ${e.localizedMessage ?: "Please try again."}" }
            AnalysisResult(contextName=context.name, summary="Failed to complete AI screen analysis", observations=listOf(safeMessage), conclusion="Please verify network connectivity and try again.", rawResponse="", isSuccess=false, errorMessage=safeMessage, processingDurationMs=System.currentTimeMillis()-startTime)
        }
    }

    override suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey.isNullOrBlank()) return@withContext ConnectionTestResult.Error("API key is not configured. Please enter a valid Gemini API key.")
        discoverModels().fold(onSuccess={models -> ConnectionTestResult.Success("Connection successful! Discovered ${models.size} available Gemini model(s).", models)}, onFailure={error -> ConnectionTestResult.Error(error.localizedMessage ?: "Connection test failed.")})
    }

    override suspend fun discoverModels(): Result<List<GeminiModel>> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey.isNullOrBlank()) return@withContext Result.failure(IllegalStateException("API key missing. Configure your Gemini API key in Settings."))
        val allModels=mutableListOf<GeminiModel>(); val seenPageTokens=mutableSetOf<String>(); var nextPageToken:String?=null
        try {
            do {
                val url=if(nextPageToken.isNullOrBlank()) getListModelsUrl() else "${getListModelsUrl()}?pageToken=$nextPageToken"
                val request=Request.Builder().url(url).addHeader("x-goog-api-key",apiKey).get().build()
                val (status,body,success)=executeCancellationAwareCall(request).use{r->Triple(r.code,r.body?.string().orEmpty(),r.isSuccessful)}
                if(!success){if(status==429)_rateLimitState.value=RateLimitState.RATE_LIMITED; return@withContext Result.failure(RuntimeException(sanitizeHttpError(status,body)))}
                _rateLimitState.value=RateLimitState.NORMAL
                val page=parseModelsPage(body); allModels.addAll(page.models); val token=page.nextPageToken
                if(token.isNullOrBlank()||seenPageTokens.contains(token)) nextPageToken=null else {seenPageTokens.add(token);nextPageToken=token}
            } while(nextPageToken!=null)
            Result.success(allModels)
        } catch(e:CancellationException){throw e} catch(e:IllegalArgumentException){Result.failure(e)} catch(e:Exception){Result.failure(RuntimeException("Connection test failed: ${e.localizedMessage ?: "Check network connection."}",e))}
    }

    private suspend fun executeCancellationAwareCall(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request); continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback{override fun onResponse(call:Call,response:Response){continuation.resume(response)};override fun onFailure(call:Call,e:IOException){if(!continuation.isCancelled)continuation.resumeWithException(e)}})
    }

    data class ModelsPage(val models: List<GeminiModel>, val nextPageToken: String?)

    fun parseModelsPage(jsonString: String): ModelsPage {
        val root=try{JSONObject(jsonString)}catch(e:Exception){throw IllegalArgumentException("MALFORMED_MODEL_RESPONSE: Invalid JSON format",e)}
        if(!root.has("models"))throw IllegalArgumentException("MALFORMED_MODEL_RESPONSE: Missing 'models' property in response")
        val arr=root.optJSONArray("models")?:throw IllegalArgumentException("MALFORMED_MODEL_RESPONSE: 'models' must be an array")
        val list=mutableListOf<GeminiModel>()
        for(i in 0 until arr.length()){
            val obj=arr.optJSONObject(i)?:continue; val name=obj.optString("name","").trim(); if(name.isBlank())continue
            val id=normalizeModelId(name); val display=obj.optString("displayName",id).ifBlank{id}; val desc=obj.optString("description","")
            val methodsArr=obj.optJSONArray("supportedGenerationMethods"); val methods=mutableListOf<String>(); if(methodsArr!=null)for(j in 0 until methodsArr.length()){val m=methodsArr.optString(j);if(m.isNotBlank())methods.add(m)}
            val input=if(obj.has("inputTokenLimit"))obj.optInt("inputTokenLimit")else null; val output=if(obj.has("outputTokenLimit"))obj.optInt("outputTokenLimit")else null
            val version=if(obj.has("version")&&!obj.isNull("version"))obj.optString("version")else null; val base=if(obj.has("baseModelId")&&!obj.isNull("baseModelId"))obj.optString("baseModelId")else null
            list.add(GeminiModel(name,display,desc,methods,input,output,version,base))
        }
        return ModelsPage(list,root.optString("nextPageToken","").trim().ifBlank{null})
    }

    fun parseModelsList(jsonString: String): List<GeminiModel> = parseModelsPage(jsonString).models

    private fun sanitizeHttpError(statusCode:Int,responseBody:String):String=when(statusCode){400->"Invalid request parameters.";401->"INVALID_API_KEY: API key is invalid.";403->"UNAUTHORIZED: API key is unauthorized or permission denied.";404->"MODEL_NOT_FOUND: Gemini endpoint or model was not found.";408->"Request timeout from server. Please try again.";429->"RATE_LIMITED: Gemini API rate limit reached.";in 500..599->"Gemini service temporarily unavailable.";else->"HTTP $statusCode: Unexpected API error."}

    private fun parseGeminiResponseContent(jsonString: String): String {
        return try {
            val root=JSONObject(jsonString); val candidates=root.optJSONArray("candidates")
            if(candidates!=null&&candidates.length()>0){val content=candidates.getJSONObject(0).optJSONObject("content");val parts=content?.optJSONArray("parts");if(parts!=null&&parts.length()>0){val sb=StringBuilder();for(i in 0 until parts.length())sb.append(parts.getJSONObject(i).optString("text",""));sb.toString().trim()}else ""}else ""
        } catch(e:Exception){""}
    }

    private fun parseStructuredResponse(rawContent:String):Triple<String,List<String>,String>{
        if(rawContent.isBlank())return Triple("No response content returned by AI.",emptyList(),"")
        try{val clean=rawContent.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim();val json=JSONObject(clean);val summary=json.optString("summary","").trim();val obs=mutableListOf<String>();val arr=json.optJSONArray("observations");if(arr!=null)for(i in 0 until arr.length()){val o=arr.optString(i,"").trim();if(o.isNotEmpty())obs.add(o)};val conclusion=json.optString("conclusion","").trim();if(summary.isNotEmpty()||obs.isNotEmpty()||conclusion.isNotEmpty())return Triple(summary,obs,conclusion)}catch(_:Exception){}
        val lines=rawContent.lines().map{it.trim()}.filter{it.isNotEmpty()};val summary=StringBuilder();val observations=mutableListOf<String>();val conclusion=StringBuilder();var section=""
        for(line in lines){val upper=line.uppercase();when{upper.startsWith("SUMMARY:")->{section="summary";val c=line.substringAfter("SUMMARY:","").trim();if(c.isNotEmpty())summary.append(c).append(" ")};upper.startsWith("OBSERVATIONS:")||upper.startsWith("OBSERVATION:")||upper.startsWith("FINDINGS:")->section="observations";upper.startsWith("CONCLUSION:")||upper.startsWith("TAKEAWAYS:")||upper.startsWith("TAKEAWAY:")->section="conclusion";else->when(section){"summary"->summary.append(line).append(" ");"observations"->{val c=line.removePrefix("-").removePrefix("*").removePrefix("•").trim();if(c.isNotEmpty())observations.add(c)};"conclusion"->conclusion.append(line).append(" ");else->if(line.startsWith("-")||line.startsWith("*")||line.startsWith("•"))observations.add(line.removePrefix("-").removePrefix("*").removePrefix("•").trim())else if(summary.isEmpty())summary.append(line).append(" ")}}}
        val finalSummary=summary.toString().trim().ifEmpty{rawContent.take(200)+if(rawContent.length>200)"..."else ""};return Triple(finalSummary,observations,conclusion.toString().trim())
    }

    private data class ResponseSummary(val statusCode:Int,val bodyString:String,val isSuccess:Boolean,val retryAfter:String?)
}
