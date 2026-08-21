package com.example.ai

import android.graphics.Bitmap
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderConcurrencyTest {
    private val context = AnalysisContext.DEFAULT
    private val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @Test
    fun routerSerializesConcurrentAnalysis() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val provider = BlockingProvider(active, maximum)
        val router = AiProviderRouter(listOf(provider))

        val first = async { router.analyze(bitmap, context, CaptureSettings.DEFAULT) }
        val second = async { router.analyze(bitmap, context, CaptureSettings.DEFAULT) }

        first.await()
        second.await()

        assertEquals(1, maximum.get())
        assertEquals(2, provider.completed.get())
    }

    @Test
    fun routerCancellationDoesNotWaitForAnalysisMutex() = runBlocking {
        val provider = BlockingProvider(AtomicInteger(0), AtomicInteger(0))
        val router = AiProviderRouter(listOf(provider))
        val analysis = launch { router.analyze(bitmap, context, CaptureSettings.DEFAULT) }

        while (!provider.started.get()) yield()
        router.cancel(AiProviderType.GEMINI)

        assertTrue(provider.cancelled.get())
        analysis.cancel()
        analysis.join()
    }

    private class BlockingProvider(
        private val active: AtomicInteger,
        private val maximum: AtomicInteger
    ) : AiProvider {
        override val type = AiProviderType.GEMINI
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val completed = AtomicInteger(0)

        override suspend fun analyze(
            bitmap: Bitmap,
            context: AnalysisContext,
            settings: CaptureSettings,
            userPrompt: String?
        ): AnalysisResult {
            started.set(true)
            val now = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, now) }
            try {
                delay(25)
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
            cancelled.set(true)
        }
    }
}
