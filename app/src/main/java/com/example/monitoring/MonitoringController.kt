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

    private var loopJob: Job? = null
    private val sessionCounter = AtomicLong(0)
    private val loopMutex = Mutex()

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
     * - Stale jobs from prior sessions cannot publish results, bitmaps, or state.
     * - Delay strictly runs AFTER the AI analysis finishes.
     */
    fun startMonitoring(
        contextProvider: () -> AnalysisContext,
        settingsProvider: () -> CaptureSettings
    ) {
        coroutineScope.launch {
            loopMutex.withLock {
                // Cancel existing job and advance session generation
                val sessionId = sessionCounter.incrementAndGet()
                loopJob?.cancel()

                _state.value = MonitoringState.Starting

                loopJob = coroutineScope.launch(Dispatchers.Default) {
                    try {
                        // Wait for ScreenCaptureEngine to be ready
                        var waitAttempts = 0
                        while (!ScreenCaptureEngine.isReady.value && waitAttempts < 25 && isActive) {
                            if (sessionId != sessionCounter.get()) return@launch
                            delay(100)
                            waitAttempts++
                        }

                        if (!ScreenCaptureEngine.isReady.value) {
                            if (sessionId == sessionCounter.get()) {
                                _state.value = MonitoringState.Error("Screen capture session is not active. Please start monitoring again.")
                            }
                            return@launch
                        }

                        // Continuous Sequential Loop
                        while (isActive && sessionId == sessionCounter.get()) {
                            // 1. CAPTURE SCREEN
                            if (sessionId != sessionCounter.get()) return@launch
                            _state.value = MonitoringState.Capturing

                            val captureResult = ScreenCaptureEngine.captureSingleFrame()
                            if (sessionId != sessionCounter.get()) return@launch

                            val capturedBitmap = when (captureResult) {
                                is CaptureResult.Success -> {
                                    _latestBitmap.value = captureResult.bitmap
                                    _lastCaptureTimestamp.value = System.currentTimeMillis()
                                    captureResult.bitmap
                                }
                                is CaptureResult.Error -> {
                                    if (sessionId == sessionCounter.get()) {
                                        _state.value = MonitoringState.Error(captureResult.message)
                                    }
                                    return@launch
                                }
                            }

                            // 2. SEND TO AI
                            if (sessionId != sessionCounter.get()) return@launch
                            val currentContext = contextProvider()
                            val currentSettings = settingsProvider()
                            _state.value = MonitoringState.Analyzing(startTimeMs = System.currentTimeMillis())

                            // 3. AI PROCESSING (Wait for complete response)
                            val result = visionAnalyzer.analyze(
                                bitmap = capturedBitmap,
                                context = currentContext,
                                settings = currentSettings
                            )

                            if (sessionId != sessionCounter.get() || !isActive) return@launch
                            _latestResult.value = result
                            _analysisCount.value += 1

                            // 4. DELAY TIMER (Strictly starts AFTER AI completes)
                            val delaySeconds = currentSettings.delaySeconds.coerceIn(1, 600)
                            for (remaining in delaySeconds downTo 1) {
                                if (sessionId != sessionCounter.get() || !isActive) return@launch
                                _state.value = MonitoringState.Waiting(
                                    remainingSeconds = remaining,
                                    totalSeconds = delaySeconds
                                )
                                delay(1000L)
                            }
                        }
                    } catch (e: CancellationException) {
                        if (sessionId == sessionCounter.get()) {
                            _state.value = MonitoringState.Idle
                        }
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
            }
        }
    }

    /**
     * Safely and idempotently stops the monitoring loop.
     */
    fun stopMonitoring() {
        sessionCounter.incrementAndGet()
        loopJob?.cancel()
        loopJob = null
        _state.value = MonitoringState.Idle
    }

    fun resetState() {
        stopMonitoring()
        _latestResult.value = null
        _analysisCount.value = 0
        _lastCaptureTimestamp.value = null
    }
}
