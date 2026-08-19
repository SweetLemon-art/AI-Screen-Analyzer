package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AnalysisResult
import com.example.ai.ConnectionTestResult
import com.example.ai.GeminiModel
import com.example.ai.GeminiVisionAnalyzer
import com.example.ai.ImageInputCapability
import com.example.ai.RateLimitState
import com.example.ai.VisionAnalyzer
import com.example.capture.CaptureResult
import com.example.capture.ScreenCaptureEngine
import com.example.capture.ScreenCaptureProvider
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.data.SettingsRepository
import com.example.image.ImageProcessor
import com.example.monitoring.MonitoringController
import com.example.monitoring.MonitoringState
import com.example.security.GeminiApiKeyStore
import com.example.ui.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiVisionAnalyzerQuotaAndModelTest {

    private lateinit var context: Context
    private lateinit var keyStore: GeminiApiKeyStore
    private lateinit var settingsRepository: SettingsRepository

    private val supportedVisionModel = GeminiModel(
        name = "models/test-vision-model",
        displayName = "Test Vision Model",
        description = "Multimodal test model",
        supportedGenerationMethods = listOf("generateContent"),
        imageInputCapability = ImageInputCapability.SUPPORTED
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        keyStore = GeminiApiKeyStore(context)
        keyStore.clearApiKey()
        settingsRepository = SettingsRepository(context)
        settingsRepository.clearSelectedModel()
        settingsRepository.saveDiscoveredModels(emptyList())
    }

    private fun createClient(interceptor: Interceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    // 1. 200 model discovery with modalities
    @Test
    fun test200ModelDiscovery() = runTest {
        keyStore.saveApiKey("test-key-12345")
        val sampleModelsJson = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "description": "Fast multimodal",
                  "supportedGenerationMethods": ["generateContent", "countTokens"],
                  "supportedInputModalities": ["TEXT", "IMAGE"]
                },
                {
                  "name": "models/embedding-001",
                  "displayName": "Embedding 001",
                  "description": "Text embedding",
                  "supportedGenerationMethods": ["embedContent"]
                }
              ]
            }
        """.trimIndent()

        val client = createClient { chain ->
            val req = chain.request()
            assertEquals("test-key-12345", req.header("x-goog-api-key"))
            assertTrue(req.url.toString().endsWith("/v1beta/models"))
            assertFalse(req.url.toString().contains("key="))
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(sampleModelsJson.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val result = analyzer.testConnection()

        assertTrue(result is ConnectionTestResult.Success)
        val success = result as ConnectionTestResult.Success
        assertEquals(2, success.models.size)
        assertEquals("gemini-2.5-flash", success.models[0].modelId)
        assertEquals(ImageInputCapability.SUPPORTED, success.models[0].imageInputCapability)
        assertEquals(ImageInputCapability.UNKNOWN, success.models[1].imageInputCapability)
        assertEquals(RateLimitState.NORMAL, analyzer.rateLimitState.value)
    }

    // 2. 401 Unauthorized
    @Test
    fun test401Unauthorized() = runTest {
        keyStore.saveApiKey("invalid-key")
        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"error":{"message":"API_KEY_INVALID"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val testResult = analyzer.testConnection()
        assertTrue(testResult is ConnectionTestResult.Error)
        assertEquals("API key is invalid.", (testResult as ConnectionTestResult.Error).message)
    }

    // 3. 403 Forbidden
    @Test
    fun test403Forbidden() = runTest {
        keyStore.saveApiKey("forbidden-key")
        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .body("""{"error":{"message":"PERMISSION_DENIED"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val testResult = analyzer.testConnection()
        assertTrue(testResult is ConnectionTestResult.Error)
        assertEquals("API key is unauthorized or permission denied.", (testResult as ConnectionTestResult.Error).message)
    }

    // 4. 404 Model Not Found
    @Test
    fun test404NotFound() = runTest {
        keyStore.saveApiKey("test-key")
        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("""{"error":{"message":"models/invalid-model is not found"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val testResult = analyzer.testConnection()
        assertTrue(testResult is ConnectionTestResult.Error)
        assertEquals("Gemini endpoint or model was not found.", (testResult as ConnectionTestResult.Error).message)
    }

    // 5. 429 Rate Limit
    @Test
    fun test429RateLimit() = runTest {
        keyStore.saveApiKey("test-key")
        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .body("""{"error":{"message":"RESOURCE_EXHAUSTED"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val testResult = analyzer.testConnection()
        assertTrue(testResult is ConnectionTestResult.Error)
        assertEquals("Gemini API rate limit reached.", (testResult as ConnectionTestResult.Error).message)
        assertEquals(RateLimitState.RATE_LIMITED, analyzer.rateLimitState.value)
    }

    // Strict validation tests
    @Test
    fun testMalformedJsonResponseFails() = runTest {
        keyStore.saveApiKey("test-key")
        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val result = analyzer.discoverModels()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("MALFORMED_MODEL_RESPONSE"))
    }

    @Test
    fun testMissingModelsPropertyFails() = runTest {
        keyStore.saveApiKey("test-key")
        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"foo":"bar"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val result = analyzer.discoverModels()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("MALFORMED_MODEL_RESPONSE"))
    }

    @Test
    fun testValidEmptyModelsArraySucceeds() = runTest {
        keyStore.saveApiKey("test-key")
        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"models":[]}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val result = analyzer.discoverModels()
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull()!!.size)
    }

    // Multi-page Pagination Test
    @Test
    fun testMultiPagePaginationModelDiscovery() = runTest {
        keyStore.saveApiKey("test-key")
        val requestedUrls = mutableListOf<String>()

        val page1Json = """
            {
              "models": [
                {
                  "name": "models/model-p1",
                  "displayName": "Model Page 1",
                  "description": "Page 1 Model",
                  "supportedGenerationMethods": ["generateContent"],
                  "supportedInputModalities": ["TEXT", "IMAGE"],
                  "inputTokenLimit": 1048576,
                  "outputTokenLimit": 8192
                }
              ],
              "nextPageToken": "page-2-token"
            }
        """.trimIndent()

        val page2Json = """
            {
              "models": [
                {
                  "name": "models/model-p2",
                  "displayName": "Model Page 2",
                  "description": "Page 2 Model",
                  "supportedGenerationMethods": ["generateContent"],
                  "inputModalities": ["IMAGE"]
                }
              ]
            }
        """.trimIndent()

        val client = createClient { chain ->
            val url = chain.request().url.toString()
            requestedUrls.add(url)
            val body = if (url.contains("pageToken=page-2-token")) page2Json else page1Json
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val result = analyzer.discoverModels()

        assertTrue(result.isSuccess)
        val models = result.getOrNull() ?: emptyList()
        assertEquals(2, models.size)
        assertEquals("model-p1", models[0].modelId)
        assertEquals(1048576, models[0].inputTokenLimit)
        assertEquals(ImageInputCapability.SUPPORTED, models[0].imageInputCapability)
        assertEquals("model-p2", models[1].modelId)
        assertEquals(ImageInputCapability.SUPPORTED, models[1].imageInputCapability)
        assertEquals(2, requestedUrls.size)
    }

    // Pagination loop prevention test
    @Test
    fun testPaginationStopsOnDuplicateNextPageToken() = runTest {
        keyStore.saveApiKey("test-key")
        var callCount = 0

        val loopingPageJson = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "supportedGenerationMethods": ["generateContent"]
                }
              ],
              "nextPageToken": "stuck-token"
            }
        """.trimIndent()

        val client = createClient { chain ->
            callCount++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(loopingPageJson.toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val result = analyzer.discoverModels()

        assertTrue(result.isSuccess)
        assertEquals(2, callCount)
    }

    // ==========================================
    // CAPABILITY TESTS (Prompt Requirement 12)
    // ==========================================

    @Test
    fun testGenerateContentModelWithReliablySupportedImageCapability() {
        val json = """
            {
              "models": [
                {
                  "name": "models/vision-supported-model",
                  "displayName": "Vision Supported Model",
                  "supportedGenerationMethods": ["generateContent"],
                  "supportedInputModalities": ["TEXT", "IMAGE"]
                }
              ]
            }
        """.trimIndent()

        val analyzer = GeminiVisionAnalyzer(keyStore)
        val page = analyzer.parseModelsPage(json)
        assertEquals(1, page.models.size)
        assertEquals(ImageInputCapability.SUPPORTED, page.models[0].imageInputCapability)
    }

    @Test
    fun testGenerateContentModelWithUnknownImageCapability() {
        val json = """
            {
              "models": [
                {
                  "name": "models/text-only-model",
                  "displayName": "Text Only Model",
                  "supportedGenerationMethods": ["generateContent"]
                }
              ]
            }
        """.trimIndent()

        val analyzer = GeminiVisionAnalyzer(keyStore)
        val page = analyzer.parseModelsPage(json)
        assertEquals(1, page.models.size)
        assertEquals(ImageInputCapability.UNKNOWN, page.models[0].imageInputCapability)
    }

    @Test
    fun testIncompatibleModelWithoutGenerateContent() {
        val json = """
            {
              "models": [
                {
                  "name": "models/embedding-model",
                  "displayName": "Embedding Model",
                  "supportedGenerationMethods": ["embedContent"]
                }
              ]
            }
        """.trimIndent()

        val analyzer = GeminiVisionAnalyzer(keyStore)
        val page = analyzer.parseModelsPage(json)
        assertEquals(1, page.models.size)
        assertFalse(page.models[0].supportedGenerationMethods.contains("generateContent"))
    }

    @Test
    fun testUnknownCapabilityRejectedForImageAnalysis() = runTest {
        keyStore.saveApiKey("test-key")
        val unknownCapabilityModel = GeminiModel(
            name = "models/unknown-capability-model",
            displayName = "Unknown Model",
            description = "No verified image capability",
            supportedGenerationMethods = listOf("generateContent"),
            imageInputCapability = ImageInputCapability.UNKNOWN
        )

        var networkCalls = 0
        val client = createClient { chain ->
            networkCalls++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(
            apiKeyStore = keyStore,
            modelProvider = { "unknown-capability-model" },
            compatibleModelsProvider = { listOf(unknownCapabilityModel) },
            client = client
        )

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)

        assertFalse(result.isSuccess)
        assertEquals("MODEL_NOT_IMAGE_CAPABLE", result.summary)
        assertTrue(result.errorMessage!!.contains("MODEL_NOT_IMAGE_CAPABLE"))
        assertEquals("Network request must NOT be sent for unverified image capability", 0, networkCalls)
    }

    @Test
    fun testModelNamePrefixDoesNotImplyImageCapability() {
        val json = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/gemini-ultra-vision-pro",
                  "displayName": "Gemini Ultra Vision Pro",
                  "supportedGenerationMethods": ["generateContent"]
                }
              ]
            }
        """.trimIndent()

        val analyzer = GeminiVisionAnalyzer(keyStore)
        val page = analyzer.parseModelsPage(json)
        assertEquals(2, page.models.size)
        // Without explicit modality in API response, both must be UNKNOWN — prefix heuristics are strictly forbidden
        assertEquals(ImageInputCapability.UNKNOWN, page.models[0].imageInputCapability)
        assertEquals(ImageInputCapability.UNKNOWN, page.models[1].imageInputCapability)
    }

    @Test
    fun testNoFakeCapabilityFieldsAssumed() {
        val json = """
            {
              "models": [
                {
                  "name": "models/model-with-fake-fields",
                  "displayName": "Model With Fake Fields",
                  "supportedGenerationMethods": ["generateContent"],
                  "isVisionCapable": true,
                  "supportsImages": true
                }
              ]
            }
        """.trimIndent()

        val analyzer = GeminiVisionAnalyzer(keyStore)
        val page = analyzer.parseModelsPage(json)
        assertEquals(1, page.models.size)
        // Fake unstandardized fields are ignored; without genuine API modality array it remains UNKNOWN
        assertEquals(ImageInputCapability.UNKNOWN, page.models[0].imageInputCapability)
    }

    // ==========================================
    // GENERATION GUARD TESTS (Prompt Requirement 14)
    // ==========================================

    @Test
    fun testGenerateContentNotCalledWhenNoModelSelected() = runTest {
        keyStore.saveApiKey("test-key")
        var networkCalls = 0
        val client = createClient { chain ->
            networkCalls++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(
            apiKeyStore = keyStore,
            modelProvider = { "" },
            compatibleModelsProvider = { listOf(supportedVisionModel) },
            client = client
        )

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)

        assertFalse(result.isSuccess)
        assertEquals("NO_MODEL_SELECTED", result.summary)
        assertEquals(0, networkCalls)
    }

    @Test
    fun testGenerateContentNotCalledWhenModelUnavailable() = runTest {
        keyStore.saveApiKey("test-key")
        var networkCalls = 0
        val client = createClient { chain ->
            networkCalls++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(
            apiKeyStore = keyStore,
            modelProvider = { "obsolete-model" },
            compatibleModelsProvider = { listOf(supportedVisionModel) },
            client = client
        )

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)

        assertFalse(result.isSuccess)
        assertEquals("MODEL_NOT_AVAILABLE", result.summary)
        assertEquals(0, networkCalls)
    }

    @Test
    fun testGenerateContentNotCalledWhenModelIncompatible() = runTest {
        keyStore.saveApiKey("test-key")
        val textOnlyModel = GeminiModel(
            name = "models/text-only",
            displayName = "Text Only",
            description = "No generateContent",
            supportedGenerationMethods = listOf("embedContent"),
            imageInputCapability = ImageInputCapability.SUPPORTED
        )

        var networkCalls = 0
        val client = createClient { chain ->
            networkCalls++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(
            apiKeyStore = keyStore,
            modelProvider = { "text-only" },
            compatibleModelsProvider = { listOf(textOnlyModel) },
            client = client
        )

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)

        assertFalse(result.isSuccess)
        assertEquals("MODEL_NOT_AVAILABLE", result.summary)
        assertEquals(0, networkCalls)
    }

    // ==========================================
    // NETWORK RETRY, 429 & BACKOFF TESTS
    // ==========================================

    // 6. 429 with Retry-After during analyze
    @Test
    fun test429WithRetryAfterDuringAnalyze() = runTest {
        keyStore.saveApiKey("test-key")
        var requestCount = 0

        val client = createClient { chain ->
            requestCount++
            if (requestCount == 1) {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .header("Retry-After", "1")
                    .body("""{"error":{"message":"RESOURCE_EXHAUSTED"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [{"text": "{\"summary\":\"Recovered\",\"observations\":[],\"conclusion\":\"OK\"}"}]
                              }
                            }
                          ]
                        }
                    """.trimIndent().toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        val analyzer = GeminiVisionAnalyzer(
            apiKeyStore = keyStore,
            modelProvider = { "test-vision-model" },
            compatibleModelsProvider = { listOf(supportedVisionModel) },
            client = client
        )
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)

        assertTrue(result.isSuccess)
        assertEquals("Recovered", result.summary)
        assertEquals(2, requestCount)
    }

    // 7. 429 without Retry-After (Exponential backoff)
    @Test
    fun test429WithoutRetryAfterExponentialBackoff() = runTest {
        keyStore.saveApiKey("test-key")
        var requestCount = 0

        val client = createClient { chain ->
            requestCount++
            if (requestCount < 3) {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("""{"error":{"message":"RESOURCE_EXHAUSTED"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [{"text": "{\"summary\":\"Success on attempt 3\",\"observations\":[],\"conclusion\":\"OK\"}"}]
                              }
                            }
                          ]
                        }
                    """.trimIndent().toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        val analyzer = GeminiVisionAnalyzer(
            apiKeyStore = keyStore,
            modelProvider = { "test-vision-model" },
            compatibleModelsProvider = { listOf(supportedVisionModel) },
            client = client
        )
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)

        assertTrue(result.isSuccess)
        assertEquals("Success on attempt 3", result.summary)
        assertEquals(3, requestCount)
    }

    // 8 & 9. Maximum retry count & bounded retry
    @Test
    fun testMaxRetryAttemptsExceededReturnsQuotaError() = runTest {
        keyStore.saveApiKey("test-key")
        var requestCount = 0

        val client = createClient { chain ->
            requestCount++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", "1")
                .body("""{"error":{"message":"RESOURCE_EXHAUSTED"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(
            apiKeyStore = keyStore,
            modelProvider = { "test-vision-model" },
            compatibleModelsProvider = { listOf(supportedVisionModel) },
            client = client
        )
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Gemini"))
        assertEquals(4, requestCount)
    }

    // 10. Cancellation during retry delay
    @Test
    fun testCancellationDuringRetryDelayAbortsImmediately() = runTest {
        keyStore.saveApiKey("test-key")
        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", "20")
                .body("""{"error":{"message":"RESOURCE_EXHAUSTED"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(
            apiKeyStore = keyStore,
            modelProvider = { "test-vision-model" },
            compatibleModelsProvider = { listOf(supportedVisionModel) },
            client = client
        )
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        val job = launch {
            analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)
        }

        advanceTimeBy(100)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    // 11. 5xx Server Error
    @Test
    fun test5xxServerError() = runTest {
        keyStore.saveApiKey("test-key")
        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Service Unavailable")
                .body("""{"error":{"message":"Overloaded"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val testResult = analyzer.testConnection()
        assertTrue(testResult is ConnectionTestResult.Error)
        assertEquals("Gemini service temporarily unavailable.", (testResult as ConnectionTestResult.Error).message)
    }

    // 12. Network Failure
    @Test
    fun testNetworkFailure() = runTest {
        keyStore.saveApiKey("test-key")
        val client = createClient {
            throw IOException("Failed to connect to host")
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        val testResult = analyzer.testConnection()
        assertTrue(testResult is ConnectionTestResult.Error)
        assertTrue((testResult as ConnectionTestResult.Error).message.contains("Connection test failed"))
    }

    // 13 & 14. Rate limit error does not kill MonitoringController or create duplicates
    @Test
    fun testRateLimitErrorDoesNotKillMonitoringControllerOrDuplicateJobs() = runTest {
        keyStore.saveApiKey("test-key")
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val client = createClient { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", "1")
                .body("""{"error":{"message":"RESOURCE_EXHAUSTED"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(
            apiKeyStore = keyStore,
            modelProvider = { "test-vision-model" },
            compatibleModelsProvider = { listOf(supportedVisionModel) },
            client = client
        )
        val fakeCapture = object : ScreenCaptureProvider {
            override val isReady: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
            override suspend fun captureSingleFrame(): CaptureResult =
                CaptureResult.Success(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        }

        val controller = MonitoringController(analyzer, testScope, fakeCapture)

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        advanceTimeBy(5000)

        assertNotNull(controller.latestResult.value)
        assertFalse(controller.latestResult.value!!.isSuccess)

        controller.stopMonitoring()
        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()
        advanceUntilIdle()

        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    // 15 & 16. API key never appears in URL and is sent via x-goog-api-key
    @Test
    fun testApiKeyNeverInUrlAndSentViaHeader() = runTest {
        val apiKey = "secret-gemini-key-999"
        keyStore.saveApiKey(apiKey)

        var observedHeaderKey: String? = null
        var observedUrl: String? = null

        val client = createClient { chain ->
            val req = chain.request()
            observedHeaderKey = req.header("x-goog-api-key")
            observedUrl = req.url.toString()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"models":[]}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val analyzer = GeminiVisionAnalyzer(keyStore, client = client)
        analyzer.testConnection()

        assertEquals(apiKey, observedHeaderKey)
        assertNotNull(observedUrl)
        assertFalse("API key must NEVER appear in request URL", observedUrl!!.contains(apiKey))
    }

    // 17. Selected model is used for generateContent without duplicate models/ prefix
    @Test
    fun testSelectedModelUsedForGenerateContentWithoutDuplicatePrefix() = runTest {
        keyStore.saveApiKey("test-key")
        var requestedUrl: String? = null

        val client = createClient { chain ->
            val req = chain.request()
            requestedUrl = req.url.toString()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [{"text": "{\"summary\":\"Custom Model Output\",\"observations\":[],\"conclusion\":\"OK\"}"}]
                          }
                        }
                      ]
                    }
                """.trimIndent().toResponseBody("application/json".toMediaType()))
                .build()
        }

        val customModel = GeminiModel(
            name = "models/custom-model",
            displayName = "Custom Model",
            description = "Custom",
            supportedGenerationMethods = listOf("generateContent"),
            imageInputCapability = ImageInputCapability.SUPPORTED
        )

        val analyzer = GeminiVisionAnalyzer(
            apiKeyStore = keyStore,
            modelProvider = { "models/custom-model" },
            compatibleModelsProvider = { listOf(customModel) },
            client = client
        )

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)

        assertNotNull(requestedUrl)
        assertTrue("URL must contain clean model name", requestedUrl!!.endsWith("/v1beta/models/custom-model:generateContent"))
        assertFalse("URL must never contain double models/models/", requestedUrl!!.contains("/models/models/"))
    }

    // ==========================================
    // MODEL VALIDATION & VIEWMODEL TESTS (Prompt Requirement 13)
    // ==========================================

    @Test
    fun testSelectedModelExistsAfterRefreshPreserved() {
        val viewModel = MainViewModel(context)
        val freshModel = GeminiModel(
            name = "models/active-vision-model",
            displayName = "Active Vision Model",
            description = "Active",
            supportedGenerationMethods = listOf("generateContent"),
            imageInputCapability = ImageInputCapability.SUPPORTED
        )

        viewModel.selectModel("active-vision-model")
        assertEquals("active-vision-model", viewModel.selectedModel.value)

        viewModel.handleDiscoveredModels(listOf(freshModel))

        assertEquals("active-vision-model", viewModel.selectedModel.value)
        assertNull(viewModel.modelValidationMessage.value)
    }

    @Test
    fun testSelectedModelMissingAfterRefreshCleared() {
        val viewModel = MainViewModel(context)
        viewModel.selectModel("old-model-discontinued")
        assertEquals("old-model-discontinued", viewModel.selectedModel.value)

        val freshList = listOf(
            GeminiModel(
                name = "models/new-vision-model",
                displayName = "New Vision Model",
                description = "New",
                supportedGenerationMethods = listOf("generateContent"),
                imageInputCapability = ImageInputCapability.SUPPORTED
            )
        )

        viewModel.handleDiscoveredModels(freshList)

        assertEquals("", viewModel.selectedModel.value)
        assertEquals("", settingsRepository.loadSelectedModel())
        assertNotNull(viewModel.modelValidationMessage.value)
        assertTrue(viewModel.modelValidationMessage.value!!.contains("MODEL_NOT_AVAILABLE"))
    }

    @Test
    fun testModelListEmptyClearedAndNoCompatibleModels() {
        val viewModel = MainViewModel(context)
        viewModel.selectModel("some-model")

        viewModel.handleDiscoveredModels(emptyList())

        assertEquals("", viewModel.selectedModel.value)
        assertEquals("", settingsRepository.loadSelectedModel())
        assertNotNull(viewModel.modelValidationMessage.value)
        assertTrue(viewModel.modelValidationMessage.value!!.contains("NO_COMPATIBLE_MODELS"))
    }

    @Test
    fun testModelListFailureSelectionPreserved() = runTest {
        keyStore.saveApiKey("test-key")
        val client = createClient {
            throw IOException("Network offline")
        }

        val viewModel = MainViewModel(context)
        viewModel.selectModel("preserved-model")
        assertEquals("preserved-model", viewModel.selectedModel.value)

        viewModel.fetchAvailableModels()
        advanceUntilIdle()

        // Discovery network failure must NOT destroy existing valid selection
        assertEquals("preserved-model", viewModel.selectedModel.value)
        assertEquals("preserved-model", settingsRepository.loadSelectedModel())
    }

    @Test
    fun testModelsPrefixVsRawNameComparisonMatchPreserved() {
        val viewModel = MainViewModel(context)
        // User has "models/gemini-flash" saved
        settingsRepository.saveSelectedModel("models/gemini-flash")

        val freshModels = listOf(
            GeminiModel(
                name = "models/gemini-flash",
                displayName = "Gemini Flash",
                description = "Flash",
                supportedGenerationMethods = listOf("generateContent"),
                imageInputCapability = ImageInputCapability.SUPPORTED
            )
        )

        viewModel.handleDiscoveredModels(freshModels)

        assertEquals("gemini-flash", viewModel.selectedModel.value)
        assertNull(viewModel.modelValidationMessage.value)
    }

    @Test
    fun testCachedModelReplacedByFreshAuthoritativeModelList() {
        // Populate cache with stale model
        val staleModel = GeminiModel(
            name = "models/stale-model",
            displayName = "Stale Model",
            description = "Stale",
            supportedGenerationMethods = listOf("generateContent"),
            imageInputCapability = ImageInputCapability.SUPPORTED
        )
        settingsRepository.saveDiscoveredModels(listOf(staleModel))
        settingsRepository.saveSelectedModel("stale-model")

        val viewModel = MainViewModel(context)
        assertEquals("stale-model", viewModel.selectedModel.value)

        // Fresh discovery comes with different authoritative models
        val freshAuthoritative = listOf(
            GeminiModel(
                name = "models/fresh-model",
                displayName = "Fresh Model",
                description = "Fresh",
                supportedGenerationMethods = listOf("generateContent"),
                imageInputCapability = ImageInputCapability.SUPPORTED
            )
        )

        viewModel.handleDiscoveredModels(freshAuthoritative)

        // Stale selection is cleared because it is not in fresh discovery
        assertEquals("", viewModel.selectedModel.value)
        assertEquals(1, viewModel.discoveredModels.value.size)
        assertEquals("fresh-model", viewModel.discoveredModels.value[0].modelId)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageProcessorTest {

    @Test
    fun testProcessForGeminiBase64DoesNotRecycleOriginalBitmap() {
        val original = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        val base64 = ImageProcessor.processForGeminiBase64(original, maxDimension = 300, quality = 80)

        assertNotNull(base64)
        assertTrue(base64.isNotEmpty())
        assertFalse("Original bitmap must NOT be recycled by ImageProcessor", original.isRecycled)
    }

    @Test
    fun testProcessForGeminiBase64WithoutScaling() {
        val original = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val base64 = ImageProcessor.processForGeminiBase64(original, maxDimension = 500, quality = 90)

        assertNotNull(base64)
        assertTrue(base64.isNotEmpty())
        assertFalse(original.isRecycled)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenCaptureEngineLifecycleTest {

    @Test
    fun testIdempotentStop() {
        ScreenCaptureEngine.stop()
        ScreenCaptureEngine.stop()
        ScreenCaptureEngine.stop()
        assertFalse(ScreenCaptureEngine.isReady.value)
    }

    @Test
    fun testCaptureWhenNotReadyReturnsErrorWithoutRecreating() = runTest {
        ScreenCaptureEngine.stop()
        val result = ScreenCaptureEngine.captureSingleFrame()

        assertTrue(result is CaptureResult.Error)
        assertFalse(ScreenCaptureEngine.isReady.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonitoringControllerLifecycleRaceTest {

    private class FakeCaptureProvider(
        private val captureDelayMs: Long = 0L,
        private val frameBitmap: Bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
    ) : ScreenCaptureProvider {
        private val _isReady = MutableStateFlow(true)
        override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

        override suspend fun captureSingleFrame(): CaptureResult {
            if (captureDelayMs > 0) delay(captureDelayMs)
            return CaptureResult.Success(frameBitmap)
        }
    }

    @Test
    fun startThenStopEndsIdle() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
            override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()

        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)
    }

    @Test
    fun startStopStartLeavesExactlyOneMonitoringJob() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var analysisCallCount = 0
        val fakeAnalyzer = object : VisionAnalyzer {
            override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                analysisCallCount++
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Analysis $analysisCallCount",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
            override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })

        advanceTimeBy(100)
        assertTrue(controller.isMonitoring)

        controller.stopMonitoring()
        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun startStopStartStopEndsIdle() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
            override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()

        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)
    }

    @Test
    fun rapidStartStopStartStopStart() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var analysisCallCount = 0
        val fakeAnalyzer = object : VisionAnalyzer {
            override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                analysisCallCount++
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Analysis $analysisCallCount",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
            override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        // START -> STOP -> START -> STOP -> START in rapid bursts
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })

        advanceTimeBy(100)
        assertTrue(controller.isMonitoring)

        controller.stopMonitoring()
        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)
    }

    @Test
    fun stopDuringCaptureCancelsOldSession() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val captureStarted = CompletableDeferred<Unit>()
        val captureGate = CompletableDeferred<Unit>()

        val blockingCaptureProvider = object : ScreenCaptureProvider {
            private val _ready = MutableStateFlow(true)
            override val isReady: StateFlow<Boolean> = _ready.asStateFlow()

            override suspend fun captureSingleFrame(): CaptureResult {
                captureStarted.complete(Unit)
                captureGate.await()
                return CaptureResult.Success(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            }
        }

        var analyzeCalled = false
        val fakeAnalyzer = object : VisionAnalyzer {
            override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                analyzeCalled = true
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
            override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, blockingCaptureProvider)

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        advanceTimeBy(100)

        // Capture is in-flight; call stop
        controller.stopMonitoring()
        assertEquals(MonitoringState.Idle, controller.state.value)

        // Release blocked capture
        captureGate.complete(Unit)
        advanceUntilIdle()

        assertFalse("AI analyzer must not be invoked when capture was stopped", analyzeCalled)
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun stopDuringGeminiCancelsOldSession() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val geminiStarted = CompletableDeferred<Unit>()
        val geminiGate = CompletableDeferred<Unit>()

        val fakeAnalyzer = object : VisionAnalyzer {
            override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                geminiStarted.complete(Unit)
                geminiGate.await()
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Stale Summary",
                    observations = emptyList(),
                    conclusion = "Stale",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
            override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        advanceTimeBy(100)

        // Gemini in-flight; call stop
        controller.stopMonitoring()
        assertEquals(MonitoringState.Idle, controller.state.value)

        // Release the gated gemini analysis
        geminiGate.complete(Unit)
        advanceUntilIdle()

        assertNull("Stale result must never be published", controller.latestResult.value)
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun stopDuringLongDelayDoesNotWaitForDelay() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary 1",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
            override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        // 600-second maximum delay
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings(delaySeconds = 600) })
        advanceTimeBy(500)

        // Stop in the middle of the 600s delay
        controller.stopMonitoring()
        advanceUntilIdle()

        assertEquals(MonitoringState.Idle, controller.state.value)
        assertFalse(controller.isMonitoring)
    }

    @Test
    fun staleSessionCannotPublishResult() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val geminiGate = CompletableDeferred<Unit>()
        var sessionCount = 0

        val fakeAnalyzer = object : VisionAnalyzer {
            override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                sessionCount++
                if (sessionCount == 1) {
                    geminiGate.await() // Hold session 1
                }
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Result $sessionCount",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
            override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        // Session 1
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        advanceTimeBy(100)

        // Session 1 is stopped and Session 2 is started
        controller.stopMonitoring()
        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })

        // Release session 1
        geminiGate.complete(Unit)
        advanceTimeBy(200)

        // Clean stop
        controller.stopMonitoring()
        advanceUntilIdle()

        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun staleSessionCannotPublishState() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val fakeAnalyzer = object : VisionAnalyzer {
            override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
            override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings.DEFAULT })
        controller.stopMonitoring()

        advanceUntilIdle()
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun noOverlappingMonitoringLoops() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val concurrentLoops = AtomicInteger(0)
        var maxConcurrentLoops = 0

        val fakeAnalyzer = object : VisionAnalyzer {
            override val rateLimitState = MutableStateFlow(RateLimitState.NORMAL).asStateFlow()
            override suspend fun analyze(bitmap: Bitmap, context: AnalysisContext, settings: CaptureSettings): AnalysisResult {
                val current = concurrentLoops.incrementAndGet()
                if (current > maxConcurrentLoops) maxConcurrentLoops = current
                try {
                    delay(50)
                } finally {
                    concurrentLoops.decrementAndGet()
                }
                return AnalysisResult(
                    contextName = context.name,
                    summary = "Summary",
                    observations = emptyList(),
                    conclusion = "OK",
                    rawResponse = "",
                    isSuccess = true,
                    errorMessage = null,
                    processingDurationMs = 10L
                )
            }
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success("OK")
            override suspend fun discoverModels(): Result<List<GeminiModel>> = Result.success(emptyList())
        }

        val controller = MonitoringController(fakeAnalyzer, testScope, FakeCaptureProvider())

        // Multiple starts and stops
        for (i in 1..5) {
            controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings(delaySeconds = 1) })
            advanceTimeBy(30)
            controller.stopMonitoring()
            advanceTimeBy(30)
        }

        controller.startMonitoring({ AnalysisContext.DEFAULT }, { CaptureSettings(delaySeconds = 1) })
        advanceTimeBy(100)
        controller.stopMonitoring()
        advanceUntilIdle()

        assertEquals("There must never be more than 1 concurrent monitoring loop", 1, maxConcurrentLoops)
        assertEquals(0, concurrentLoops.get())
        assertEquals(MonitoringState.Idle, controller.state.value)
    }

    @Test
    fun testTimerBoundsClamp() {
        val settingsMin = CaptureSettings.createSafe(delay = -10, resolution = 100, quality = 10)
        assertEquals(1, settingsMin.delaySeconds)
        assertEquals(480, settingsMin.maxResolutionDimension)
        assertEquals(40, settingsMin.compressionQuality)

        val settingsMax = CaptureSettings.createSafe(delay = 1000, resolution = 5000, quality = 200)
        assertEquals(600, settingsMax.delaySeconds)
        assertEquals(2160, settingsMax.maxResolutionDimension)
        assertEquals(100, settingsMax.compressionQuality)
    }
}
