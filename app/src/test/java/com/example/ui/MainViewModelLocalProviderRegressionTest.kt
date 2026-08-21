package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainViewModelLocalProviderRegressionTest {

    @Test
    fun localMonitoringPreparationDoesNotRequireGeminiApiKey() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        viewModel.apiKeyStore.clearApiKey()

        val result = viewModel.prepareMonitoringProvider(AiProviderType.LOCAL)

        assertTrue("Local preparation should fail only because no local image model is installed", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("No imported local model with image capability"))
        assertFalse(message.contains("Gemini API key is required"))
    }

    @Test
    fun geminiMonitoringPreparationStillRequiresApiKey() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        viewModel.apiKeyStore.clearApiKey()

        val result = viewModel.prepareMonitoringProvider(AiProviderType.GEMINI)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Gemini API key is required"))
    }
}
