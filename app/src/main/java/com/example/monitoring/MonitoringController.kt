package com.example.monitoring

import android.graphics.Bitmap
import com.example.ai.AnalysisResult
import com.example.ai.VisionAnalyzer
import com.example.capture.CaptureResult
import com.example.capture.ScreenCaptureEngine
import com.example.capture.ScreenCaptureLifecycleProvider
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

private sealed interface LifecycleCommand {
    data class Start(
        val contextProvider: () -> AnalysisContext,
        val settingsProvider: () -> CaptureSettings
    ) : LifecycleCommand
    data object Stop : LifecycleCommand
    data object Reset : LifecycleCommand
}

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
    private val commandChannel = Channel<LifecycleCommand>(Channel.CONFLATED)

    val isMonitoring: Boolean
        get() = when (_state.value) {
            is MonitoringState.Capturing,
            is MonitoringState.Analyzing,
            is MonitoringState.Waiting,
            is MonitoringState.Starting,
            is MonitoringState.Stopping -> true
            else -> false
        }

    init {
        if (captureProvider is ScreenCaptureLifecycleProvider) {
            captureProvider.setOnSessionStoppedListener { stopMonitoring() }
        }
        coroutineScope.launch {
            for (command in commandChannel) {
                try {
                    handleLifecycleCommand(command)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = MonitoringState.Error(
                        e.localizedMessage ?: "Unexpected monitoring lifecycle error"
                    )
                }
            }
        }
    }

    private suspend fun handleLifecycleCommand(command: LifecycleCommand) {
        when (command) {
            is LifecycleCommand.Start -> {
                val newSessionId = sessionCounter.incrementAndGet()
                activeJob?.cancel()
                try { activeJob?.join() } catch (_: CancellationException) { }
                activeJob = null
                if (newSessionId != sessionCounter.get()) return
                _state.value = MonitoringState.Starting
                activeJob = coroutineScope.launch {
                    runMonitoringLoop(newSessionId, command.contextProvider, command.settingsProvider)
                }
            }
            is LifecycleCommand.Stop -> stopActiveMonitoring()
            is LifecycleCommand.Reset -> {
                stopActiveMonitoring()
                _latestBitmap.value = null
                _latestResult.value = null
                _analysisCount.value = 0
                _lastCaptureTimestamp.value = null
            }
        }
    }

    private suspend fun stopActiveMonitoring() {
        sessionCounter.incrementAndGet()
        _state.value = MonitoringState.Stopping
        val job = activeJob
        job?.cancel()
        try { job?.join() } catch (_: CancellationException) { }
        if (activeJob === job) activeJob = null
        _state.value = MonitoringState.Idle
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
            _state.value = MonitoringState.Stopping
            val job = activeJob
            if (job == null) {
                _state.value = MonitoringState.Idle
                return
            }
            job.invokeOnCompletion {
                if (activeJob === job) {
                    activeJob = null
                    if (_state.value is MonitoringState.Stopping) {
                        _state.value = MonitoringState.Idle
                    }
                }
            }
            job.cancel()
        }
    }

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
                    previewBitmap = try { ImageProcessor.createPreviewBitmap(analysisBitmap) } catch (_: Exception) { null }
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
                _state.value !is MonitoringState.Error &&
                _state.value !is MonitoringState.Stopping
            ) {
                _state.value = MonitoringState.Idle
            }
        }
    }

    /**
     * Invalidate the current session and clear UI state immediately. The generation bump prevents
     * an in-flight capture/analysis from publishing stale state after resetState() returns. The
     * queued Reset still performs serialized cancellation/join of the active monitoring Job.
     */
    fun resetState() {
        sessionCounter.incrementAndGet()
        _state.value = MonitoringState.Idle
        _latestBitmap.value = null
        _latestResult.value = null
        _analysisCount.value = 0
        _lastCaptureTimestamp.value = null

        val result = commandChannel.trySend(LifecycleCommand.Reset)
        if (result.isFailure) {
            val job = activeJob
            if (job != null) {
                job.invokeOnCompletion {
                    if (activeJob === job) activeJob = null
                }
                job.cancel()
            }
        }
    }

    private companion object {
        const val CAPTURE_READY_TIMEOUT_MS = 10_000L
    }
}
