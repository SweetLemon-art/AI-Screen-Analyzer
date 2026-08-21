package com.example.ai

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.security.GeminiApiKeyStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiVisionAnalyzerPhase17RegressionTest {
    private lateinit var keyStore: GeminiApiKeyStore

    @Before
    fun setup() {
        val context: Application = ApplicationProvider.getApplicationContext()
        keyStore = GeminiApiKeyStore(context)
        keyStore.clearApiKey()
        keyStore.saveApiKey("phase17-test-key")
    }

    private fun analyzer(client: OkHttpClient): GeminiVisionAnalyzer = GeminiVisionAnalyzer(
        apiKeyStore = keyStore,
        modelProvider = { "test-vision-model" },
        compatibleModelsProvider = {
            listOf(
                GeminiModel(
                    name = "models/test-vision-model",
                    displayName = "Test Vision",
                    description = "test",
                    supportedGenerationMethods = listOf("generateContent")
                )
            )
        },
        client = client,
        baseUrl = "https://test.invalid"
    )

    private fun response(request: okhttp3.Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    @Test
    fun transient503_retries_and_succeeds() = runTest {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            if (calls.incrementAndGet() == 1) {
                response(chain.request(), 503, "{\"error\":{\"message\":\"temporary\"}}")
            } else {
                response(chain.request(), 200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"summary\\\":\\\"ok\\\",\\\"observations\\\":[],\\\"conclusion\\\":\\\"done\\\"}\"}]}}]}")
            }
        }).build()

        val result = analyzer(client).analyze(
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
            AnalysisContext.DEFAULT,
            CaptureSettings.DEFAULT
        )

        assertTrue(result.isSuccess)
        assertEquals(2, calls.get())
    }

    @Test
    fun badRequest_doesNotRetry() = runTest {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            calls.incrementAndGet()
            response(chain.request(), 400, "{\"error\":{\"message\":\"bad request\"}}")
        }).build()

        val result = analyzer(client).analyze(
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
            AnalysisContext.DEFAULT,
            CaptureSettings.DEFAULT
        )

        assertTrue(result.isFailure)
        assertEquals(1, calls.get())
    }
}
