package com.example.monitoring

import android.graphics.Bitmap
import com.example.ai.AnalysisResult
import com.example.ai.VisionAnalyzer
import com.example.capture.CaptureResult
import com.example.capture.ScreenCaptureEngine
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class MonitoringController(
    private val visionAnalyzer: VisionAnalyzer,
    private val coroutineScope: CoroutineScope
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
    private val lifecycleMutex = Mutex()

    val isMonitoring: Boolean
        get() = when (_state.value) {
            is MonitoringState.Capturing,
            is MonitoringState.Analyzing,
            is MonitoringState.Waiting,
            is MonitoringState.Starting -> true
            else -> false
        }

    /**
     * Starts the sequential capture -> AI -> delay -> capture loop.
     * Guaranteed that:
     * - Shared lifecycle mutex serializes start and stop requests.
     * - Any previously active loop is fully cancelled and joined before a new loop begins.
     * - Obsolete / cancelled sessions cannot publish state, results, bitmaps, or errors.
     * - Delay strictly runs AFTER the AI analysis finishes.
     */
    fun startMonitoring(
        contextProvider: () -> AnalysisContext,
        settingsProvider: () -> CaptureSettings
    ) {
        val targetSession = sessionCounter.incrementAndGet()
        coroutineScope.launch {
            lifecycleMutex.withLock {
                // If another start or stop was requested while waiting for mutex, abort
                if (targetSession != sessionCounter.get()) {
                    return@launch
                }

                // 1. Wait for previously active job to fully terminate
                activeJob?.cancel()
                try {
                    activeJob?.join()
                } catch (ignored: Exception) {}
                activeJob = null

                if (targetSession != sessionCounter.get()) {
                    return@launch
                }

                _state.value = MonitoringState.Starting

                val newJob = launch(Dispatchers.Default) {
                    try {
                        // Wait for ScreenCaptureEngine to be ready
                        var waitAttempts = 0
                        while (!ScreenCaptureEngine.isReady.value && waitAttempts < 25 && isActive) {
                            if (targetSession != sessionCounter.get()) return@launch
                            delay(100)
                            waitAttempts++
                        }

                        if (!ScreenCaptureEngine.isReady.value) {
                            if (targetSession == sessionCounter.get()) {
                                _state.value = MonitoringState.Error("Screen capture session is not active. Please start monitoring again.")
                            }
                            return@launch
                        }

                        // Continuous Sequential Loop
                        while (isActive && targetSession == sessionCounter.get()) {
                            // 1. CAPTURE SCREEN
                            if (targetSession != sessionCounter.get() || !isActive) return@launch
                            _state.value = MonitoringState.Capturing

                            val captureResult = ScreenCaptureEngine.captureSingleFrame()
                            if (targetSession != sessionCounter.get() || !isActive) return@launch

                            val capturedBitmap = when (captureResult) {
                                is CaptureResult.Success -> {
                                    _latestBitmap.value = captureResult.bitmap
                                    _lastCaptureTimestamp.value = System.currentTimeMillis()
                                    captureResult.bitmap
                                }
                                is CaptureResult.Error -> {
                                    if (targetSession == sessionCounter.get()) {
                                        _state.value = MonitoringState.Error(captureResult.message)
                                    }
                                    return@launch
                                }
                            }

                            // 2. PREPARE CONTEXT & SETTINGS
                            if (targetSession != sessionCounter.get() || !isActive) return@launch
                            val currentContext = contextProvider()
                            val currentSettings = settingsProvider()
                            _state.value = MonitoringState.Analyzing(startTimeMs = System.currentTimeMillis())

                            // 3. AI PROCESSING (Wait for complete response)
                            val result = visionAnalyzer.analyze(
                                bitmap = capturedBitmap,
                                context = currentContext,
                                settings = currentSettings
                            )

                            if (targetSession != sessionCounter.get() || !isActive) return@launch
                            _latestResult.value = result
                            _analysisCount.value += 1

                            // 4. DELAY TIMER (Strictly starts AFTER AI completes)
                            val delaySeconds = currentSettings.delaySeconds.coerceIn(1, 600)
                            for (remaining in delaySeconds downTo 1) {
                                if (targetSession != sessionCounter.get() || !isActive) return@launch
                                _state.value = MonitoringState.Waiting(
                                    remainingSeconds = remaining,
                                    totalSeconds = delaySeconds
                                )
                                delay(1000L)
                            }
                        }
                    } catch (e: CancellationException) {
                        if (targetSession == sessionCounter.get()) {
                            _state.value = MonitoringState.Idle
                        }
                    } catch (e: Exception) {
                        if (targetSession == sessionCounter.get()) {
                            _state.value = MonitoringState.Error(e.localizedMessage ?: "Unexpected error during monitoring")
                        }
                    } finally {
                        if (targetSession == sessionCounter.get() &&
                            _state.value !is MonitoringState.Idle &&
                            _state.value !is MonitoringState.Error
                        ) {
                            _state.value = MonitoringState.Idle
                        }
                    }
                }

                activeJob = newJob
            }
        }
    }

    /**
     * Safely and idempotently stops the monitoring loop.
     * Guaranteed to invalidate pending starts and cancel the active job.
     */
    fun stopMonitoring() {
        val targetSession = sessionCounter.incrementAndGet()
        _state.value = MonitoringState.Idle
        coroutineScope.launch {
            lifecycleMutex.withLock {
                if (targetSession < sessionCounter.get()) {
                    return@launch
                }
                activeJob?.cancel()
                try {
                    activeJob?.join()
                } catch (ignored: Exception) {}
                activeJob = null
                _state.value = MonitoringState.Idle
            }
        }
    }

    fun resetState() {
        stopMonitoring()
        _latestResult.value = null
        _analysisCount.value = 0
        _lastCaptureTimestamp.value = null
    }
}
