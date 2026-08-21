package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    @Test
    fun analyzeForwardsUserPromptToSelectedProvider() = runBlocking {
        val provider = FakeProvider(AiProviderType.LOCAL)
        val router = AiProviderRouter(listOf(provider), initialProvider = AiProviderType.LOCAL)

        router.analyze(
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            context = AnalysisContext.DEFAULT,
            userPrompt = "What error is visible?"
        )

        assertEquals("What error is visible?", provider.lastPrompt)
    }

    @Test
    fun selectedProviderIsSnapshottedBeforeWaitingForAnalysisMutex() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val first = FakeProvider(AiProviderType.GEMINI, "gemini", firstGate, firstStarted)
        val second = FakeProvider(AiProviderType.LOCAL, "local")
        val router = AiProviderRouter(listOf(first, second))
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val activeRequest = launch {
            router.analyze(bitmap = bitmap, context = AnalysisContext.DEFAULT)
        }
        firstStarted.await()

        // This request snapshots GEMINI while the mutex is occupied by the first request.
        val queuedRequest = async {
            router.analyze(bitmap = bitmap, context = AnalysisContext.DEFAULT)
        }
        yield()

        router.select(AiProviderType.LOCAL)
        firstGate.complete(Unit)

        assertEquals("gemini", queuedRequest.await().summary)
        activeRequest.join()
    }

    @Test
    fun cancelDelegatesToSelectedProvider() = runBlocking {
        val provider = FakeProvider(AiProviderType.LOCAL)
        val router = AiProviderRouter(listOf(provider), initialProvider = AiProviderType.LOCAL)

        router.cancel()

        assertEquals(1, provider.cancelCount)
    }

    private class FakeProvider(
        override val type: AiProviderType,
        private val response: String = type.name,
        private val gate: CompletableDeferred<Unit>? = null,
        private val started: CompletableDeferred<Unit>? = null
    ) : AiProvider {
        var lastPrompt: String? = null
        var cancelCount = 0

        override suspend fun analyze(
            bitmap: Bitmap,
            context: AnalysisContext,
            settings: CaptureSettings,
            userPrompt: String?
        ): AnalysisResult {
            lastPrompt = userPrompt
            started?.complete(Unit)
            gate?.await()
            return AnalysisResult(
                contextName = context.name,
                summary = response
            )
        }

        override suspend fun cancel() {
            cancelCount += 1
        }
    }
}
