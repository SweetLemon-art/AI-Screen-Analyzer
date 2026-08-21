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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiVisionAnalyzerNetworkRetryRegressionTest {
    private lateinit var keyStore: GeminiApiKeyStore

    @Before
    fun setup() {
        val context: Application = ApplicationProvider.getApplicationContext()
        keyStore = GeminiApiKeyStore(context)
        keyStore.clearApiKey()
        keyStore.saveApiKey("network-retry-test-key")
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

    private fun successResponse(request: okhttp3.Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"summary\\\":\\\"ok\\\",\\\"observations\\\":[],\\\"conclusion\\\":\\\"done\\\"}\"}]}}]}"
                    .toResponseBody("application/json".toMediaType())
            )
            .build()

    @Test
    fun transientIOException_retries_and_succeeds() = runTest {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            if (calls.incrementAndGet() == 1) {
                throw IOException("temporary network failure")
            }
            successResponse(chain.request())
        }).build()

        val result = analyzer(client).analyze(
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
            AnalysisContext.DEFAULT,
            CaptureSettings.DEFAULT
        )

        assertTrue(result.isSuccess)
        assertEquals(2, calls.get())
    }
}
