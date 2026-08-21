package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiProviderConcurrencyTest {
    private val context = AnalysisContext.DEFAULT
    private val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @Test
    fun routerSerializesConcurrentAnalysis() = runBlocking {
        val provider = BlockingProvider()
        val router = AiProviderRouter(listOf(provider))

        val first = async { router.analyze(bitmap, context, CaptureSettings.DEFAULT) }
        provider.firstStarted.await()

        val second = async { router.analyze(bitmap, context, CaptureSettings.DEFAULT) }
        assertFalse(provider.secondStarted.isCompleted)
        assertEquals(0, provider.completed.get())

        provider.release()
        first.await()
        second.await()

        assertEquals(1, provider.maximumConcurrent.get())
        assertEquals(2, provider.completed.get())
    }

    @Test
    fun routerPinsProviderBeforeWaitingForAnalysisMutex() = runBlocking {
        val gemini = BlockingProvider(AiProviderType.GEMINI)
        val local = RecordingProvider(AiProviderType.LOCAL)
        val router = AiProviderRouter(
            listOf(gemini, local),
            AiProviderType.GEMINI
        )

        val first = async { router.analyze(bitmap, context, CaptureSettings.DEFAULT) }
        gemini.firstStarted.await()

        val queued = async { router.analyze(bitmap, context, CaptureSettings.DEFAULT) }
        router.select(AiProviderType.LOCAL)

        gemini.release()
        first.await()
        queued.await()

        assertEquals(2, gemini.completed.get())
        assertEquals(0, local.invocations.get())
    }

    @Test
    fun routerCancellationDoesNotWaitForAnalysisMutex() = runBlocking {
        val provider = BlockingProvider()
        val router = AiProviderRouter(listOf(provider))
        val analysis = launch { router.analyze(bitmap, context, CaptureSettings.DEFAULT) }

        provider.firstStarted.await()
        withTimeout(1_000L) {
            router.cancel(AiProviderType.GEMINI)
        }

        assertTrue(provider.cancelled)
        analysis.join()
        assertEquals(1, provider.completed.get())
    }

    private open class RecordingProvider(
        override val type: AiProviderType
    ) : AiProvider {
        val invocations = AtomicInteger(0)

        override suspend fun analyze(
            bitmap: Bitmap,
            context: AnalysisContext,
            settings: CaptureSettings,
            userPrompt: String?
        ): AnalysisResult {
            invocations.incrementAndGet()
            return AnalysisResult(
                contextName = context.name,
                summary = "ok",
                observations = emptyList(),
                conclusion = "ok",
                isSuccess = true
            )
        }
    }

    private class BlockingProvider(
        override val type: AiProviderType = AiProviderType.GEMINI
    ) : RecordingProvider(type) {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        private val releaseGate = CompletableDeferred<Unit>()
        private val active = AtomicInteger(0)
        val maximumConcurrent = AtomicInteger(0)
        val completed = AtomicInteger(0)
        var cancelled = false
            private set
        private var invocationCount = 0

        override suspend fun analyze(
            bitmap: Bitmap,
            context: AnalysisContext,
            settings: CaptureSettings,
            userPrompt: String?
        ): AnalysisResult {
            invocationCount += 1
            if (invocationCount == 1) firstStarted.complete(Unit) else secondStarted.complete(Unit)
            val now = active.incrementAndGet()
            maximumConcurrent.updateAndGet { previous -> maxOf(previous, now) }
            try {
                releaseGate.await()
                completed.incrementAndGet()
                return AnalysisResult(
                    contextName = context.name,
                    summary = "ok",
                    observations = emptyList(),
                    conclusion = "ok",
                    isSuccess = true
                )
            } finally {
                active.decrementAndGet()
            }
        }

        override suspend fun cancel() {
            cancelled = true
            releaseGate.complete(Unit)
        }

        fun release() {
            releaseGate.complete(Unit)
        }
    }
}
