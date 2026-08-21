package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiProviderRouterTest {
    private val gemini = FakeProvider(AiProviderType.GEMINI)
    private val local = FakeProvider(AiProviderType.LOCAL)

    @Test
    fun startsWithConfiguredProvider() {
        val router = AiProviderRouter(
            providers = listOf(gemini, local),
            initialProvider = AiProviderType.LOCAL
        )

        assertEquals(AiProviderType.LOCAL, router.selectedType())
    }

    @Test
    fun selectSwitchesProvider() {
        val router = AiProviderRouter(listOf(gemini, local))

        val result = router.select(AiProviderType.LOCAL)

        assertTrue(result.isSuccess)
        assertEquals(AiProviderType.LOCAL, router.selectedType())
    }

    @Test
    fun selectRejectsUnregisteredProvider() {
        val router = AiProviderRouter(listOf(gemini))

        val result = router.select(AiProviderType.LOCAL)

        assertTrue(result.isFailure)
        assertEquals(AiProviderType.GEMINI, router.selectedType())
    }

    @Test
    fun duplicateProviderTypesUseLastRegistration() = runBlocking {
        val first = FakeProvider(AiProviderType.GEMINI, "first")
        val second = FakeProvider(AiProviderType.GEMINI, "second")
        val router = AiProviderRouter(listOf(first, second))

        val result = router.analyze(
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            context = AnalysisContext.DEFAULT
        )

        assertEquals("second", result.summary)
    }

    private class FakeProvider(
        override val type: AiProviderType,
        private val response: String = type.name
    ) : AiProvider {
        override suspend fun analyze(
            bitmap: Bitmap,
            context: AnalysisContext,
            settings: CaptureSettings
        ): AnalysisResult = AnalysisResult(
            contextName = context.name,
            summary = response
        )
    }
}
