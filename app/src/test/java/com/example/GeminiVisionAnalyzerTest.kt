package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.ai.ConnectionTestResult
import com.example.ai.GeminiVisionAnalyzer
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.security.GeminiApiKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiVisionAnalyzerTest {

    private lateinit var context: Context
    private lateinit var keyStore: GeminiApiKeyStore
    private lateinit var analyzer: GeminiVisionAnalyzer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        keyStore = GeminiApiKeyStore(context)
        keyStore.clearApiKey()
        analyzer = GeminiVisionAnalyzer(keyStore)
    }

    @Test
    fun testAnalyzeWithoutApiKeyReturnsSafeErrorMessage() = runTest {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = analyzer.analyze(bitmap, AnalysisContext.DEFAULT, CaptureSettings.DEFAULT)

        assertFalse(result.isSuccess)
        assertEquals("API Key not configured", result.summary)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("API key missing"))
    }

    @Test
    fun testTestConnectionWithoutApiKeyReturnsError() = runTest {
        val testResult = analyzer.testConnection()
        assertTrue(testResult is ConnectionTestResult.Error)
        val err = testResult as ConnectionTestResult.Error
        assertTrue(err.message.contains("API key is not configured"))
    }
}
