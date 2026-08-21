package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun duplicateProviderTypesUseLastRegistration() {
        val first = FakeProvider(AiProviderType.GEMINI, "first")
        val second = FakeProvider(AiProviderType.GEMINI, "second")
        val router = AiProviderRouter(listOf(first, second))

        assertEquals("second", router.providerForTest(AiProviderType.GEMINI).name)
    }

    private class FakeProvider(
        override val type: AiProviderType,
        val name: String = type.name
    ) : AiProvider {
        override suspend fun analyze(
            bitmap: Bitmap,
            context: AnalysisContext,
            settings: CaptureSettings
        ): AnalysisResult = AnalysisResult(contextName = context.name, summary = name)
    }
}

private fun AiProviderRouter.providerForTest(type: AiProviderType): AiProvider {
    return javaClass.getDeclaredField("providersByType").apply { isAccessible = true }
        .get(this)
        .let { it as Map<*, *> }
        .getValue(type) as AiProvider
}
