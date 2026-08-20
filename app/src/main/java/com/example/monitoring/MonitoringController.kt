package com.example.monitoring

import android.graphics.Bitmap
import com.example.ai.AnalysisResult
import com.example.ai.VisionAnalyzer
import com.example.capture.CaptureResult
import com.example.capture.ScreenCaptureEngine
import com.example.capture.ScreenCaptureProvider
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.image.ImageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Commands handled sequentially by the single lifecycle coordinator.
 */
private sealed interface LifecycleCommand {
    data class Start(
        val contextProvider: () -> AnalysisContext,
        val settingsProvider: () -> CaptureSettings
    ) : LifecycleCommand

    data object Stop : LifecycleCommand
}

/**
 * Single lifecycle coordinator managing start/stop serialization and monitoring execution.
 *
 * Architecture:
 * UI/API -> single lifecycle command stream (Channel) -> sequential execution -> exactly ONE active monitoring Job.
 */
class MonitoringController(
    private val visionAnalyzer: VisionAnalyzer,
    private val coroutineScope: CoroutineScope,
    private val captureProvider: ScreenCaptureProvider = ScreenCaptureEngine
) {
    private val _state = MutableStateFlow<MonitoringState>(MonitoringState.Idle)
    val state: StateFlow<MonitoringState> = _state.asStateFlow()

    private val _latestBitmap = MutableStateFlow<Bitmap?>(null)
    val latestBitmap: StateFlow<Bitmap?> = _latestBitmap.asStateFlow()

    private val _latestResult = MutableStateFlow<AnalysisResult?>(null)
    val latestResult: StateFlow<AnalysisResult?> = _latestResult.asStateFlow()

    private val _analysisCount = MutableStateFlow(0)
    val analysisCount: StateFlow<Int> = _analysisCount.asStateFlow()

    private val _lastCaptureTimestamp = MutableStateFlow<Long?>(null)
    val lastCaptureTimestamp: StateFlow<Long?> = _lastCaptureTimestamp.asStateFlow()

    private var activeJob: Job? = null
    private val sessionCounter = AtomicLong(0)
    private val commandChannel = Channel<LifecycleCommand>(Channel.UNLIMITED)

    val isMonitoring: Boolean
        get() = when (_state.value) {
            is MonitoringState.Capturing,
            is MonitoringState.Analyzing,
            is MonitoringState.Waiting,
            is MonitoringState.Starting -> true
            else -> false
        }

    init {
        // Dedicated Single Lifecycle Coordinator
        coroutineScope.launch {
            for (command in commandChannel) {
                try {
                    handleLifecycleCommand(command)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Coordinator survives exceptions and remains ready for subsequent commands.
                }
            }
        }
    }

    private suspend fun handleLifecycleCommand(command: LifecycleCommand) {
        when (command) {
            is LifecycleCommand.Start -> {
                val newSessionId = sessionCounter.incrementAndGet()

                activeJob?.cancel()
                try {
                    activeJob?.join()
                } catch (ignored: Exception) {
                }
                activeJob = null

                if (newSessionId != sessionCounter.get()) return

                _state.value = MonitoringState.Starting

                val job = coroutineScope.launch {
                    runMonitoringLoop(newSessionId, command.contextProvider, command.settingsProvider)
                }
                activeJob = job
            }

            is LifecycleCommand.Stop -> {
                sessionCounter.incrementAndGet()
                _state.value = MonitoringState.Idle

                activeJob?.cancel()
                try {
                    activeJob?.join()
                } catch (ignored: Exception) {
                }
                activeJob = null
                _state.value = MonitoringState.Idle
            }
        }
    }

    fun startMonitoring(
        contextProvider: () -> AnalysisContext,
        settingsProvider: () -> CaptureSettings
    ) {
        commandChannel.trySend(LifecycleCommand.Start(contextProvider, settingsProvider))
    }

    fun stopMonitoring() {
        val result = commandChannel.trySend(LifecycleCommand.Stop)
        if (result.isFailure) {
            sessionCounter.incrementAndGet()
            activeJob?.cancel()
            _state.value = MonitoringState.Idle
        }
    }

    /**
     * Sequential execution of Capture -> AI -> Delay.
     *
     * Bitmap ownership rule:
     * - captureProvider transfers ownership of a successful capture bitmap here.
     * - A downscaled preview is retained for the UI when possible.
     * - The full-resolution analysis bitmap is recycled after AI processing finishes,
     *   including cancellation/error paths, unless it is also the UI preview bitmap.
     */
    private suspend fun runMonitoringLoop(
        sessionId: Long,
        contextProvider: () -> AnalysisContext,
        settingsProvider: () -> CaptureSettings
    ) {
        try {
            val becameReady = withTimeoutOrNull(CAPTURE_READY_TIMEOUT_MS) {
                captureProvider.isReady.first { ready ->
                    ready || sessionId != sessionCounter.get() || !currentCoroutineContext().isActive
                }
            } == true

            if (!becameReady || sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) {
                if (sessionId == sessionCounter.get() && currentCoroutineContext().isActive) {
                    _state.value = MonitoringState.Error(
                        "Screen capture session did not become ready. Please start monitoring again."
                    )
                }
                return
            }

            while (currentCoroutineContext().isActive && sessionId == sessionCounter.get()) {
                if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return
                _state.value = MonitoringState.Capturing

                var analysisBitmap: Bitmap? = null
                var previewBitmap: Bitmap? = null

                try {
                    val captureResult = captureProvider.captureSingleFrame()
                    if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return

                    analysisBitmap = when (captureResult) {
                        is CaptureResult.Success -> captureResult.bitmap
                        is CaptureResult.Error -> {
                            if (sessionId == sessionCounter.get()) {
                                _state.value = MonitoringState.Error(captureResult.message)
                            }
                            return
                        }
                    }

                    // Keep the UI responsive by retaining a bounded-size preview instead of
                    // the full-resolution capture. If the capture is already small enough,
                    // ImageProcessor returns the same object and ownership remains unchanged.
                    previewBitmap = try {
                        ImageProcessor.createPreviewBitmap(analysisBitmap)
                    } catch (e: Exception) {
                        // Preview is optional; never lose the analysis frame because preview
                        // allocation failed. The full bitmap remains owned by this controller.
                        analysisBitmap
                    }

                    if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return
                    _latestBitmap.value = previewBitmap
                    _lastCaptureTimestamp.value = System.currentTimeMillis()

                    if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return
                    val currentContext = contextProvider()
                    val currentSettings = settingsProvider()
                    _state.value = MonitoringState.Analyzing(startTimeMs = System.currentTimeMillis())

                    val result = visionAnalyzer.analyze(
                        bitmap = analysisBitmap,
                        context = currentContext,
                        settings = currentSettings
                    )

                    if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return
                    _latestResult.value = result
                    _analysisCount.value += 1

                    val delaySeconds = currentSettings.delaySeconds.coerceIn(1, 600)
                    for (remaining in delaySeconds downTo 1) {
                        if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return
                        _state.value = MonitoringState.Waiting(
                            remainingSeconds = remaining,
                            totalSeconds = delaySeconds
                        )
                        delay(1000L)
                    }
                } finally {
                    // Never recycle the bitmap still exposed to Compose as the preview.
                    // Android explicitly warns that recycling a bitmap still referenced by
                    // rendering code is unsafe. The bounded preview can be reclaimed by GC
                    // when the StateFlow/UI no longer references it.
                    if (analysisBitmap != null && analysisBitmap !== previewBitmap && !analysisBitmap.isRecycled) {
                        analysisBitmap.recycle()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (sessionId == sessionCounter.get()) {
                _state.value = MonitoringState.Error(e.localizedMessage ?: "Unexpected error during monitoring")
            }
        } finally {
            if (sessionId == sessionCounter.get() &&
                _state.value !is MonitoringState.Idle &&
                _state.value !is MonitoringState.Error
            ) {
                _state.value = MonitoringState.Idle
            }
        }
    }

    fun resetState() {
        stopMonitoring()
        _latestBitmap.value = null
        _latestResult.value = null
        _analysisCount.value = 0
        _lastCaptureTimestamp.value = null
    }

    private companion object {
        const val CAPTURE_READY_TIMEOUT_MS = 10_000L
    }
}
