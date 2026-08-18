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
     */
    fun startMonitoring(
        contextProvider: () -> AnalysisContext,
        settingsProvider: () -> CaptureSettings
    ) {
        if (isMonitoring) return

        loopJob?.cancel()
        loopJob = coroutineScope.launch(Dispatchers.Default) {
            try {
                _state.value = MonitoringState.Starting
                
                // Wait briefly for ScreenCaptureEngine to be fully ready
                var waitAttempts = 0
                while (!ScreenCaptureEngine.isReady.value && waitAttempts < 20 && isActive) {
                    delay(150)
                    waitAttempts++
                }

                if (!ScreenCaptureEngine.isReady.value) {
                    _state.value = MonitoringState.Error("Screen capture session was not ready. Please try starting again.")
                    return@launch
                }

                // Core Processing Loop
                while (isActive) {
                    // STEP 1: CAPTURE SCREEN
                    _state.value = MonitoringState.Capturing
                    val captureResult = ScreenCaptureEngine.captureSingleFrame()

                    val capturedBitmap = when (captureResult) {
                        is CaptureResult.Success -> {
                            val previousBmp = _latestBitmap.value
                            _latestBitmap.value = captureResult.bitmap
                            // Eligible for garbage collection
                            if (previousBmp != null && previousBmp != captureResult.bitmap && !previousBmp.isRecycled) {
                                previousBmp.recycle()
                            }
                            _lastCaptureTimestamp.value = System.currentTimeMillis()
                            captureResult.bitmap
                        }
                        is CaptureResult.Error -> {
                            _state.value = MonitoringState.Error("Screen capture failed: ${captureResult.message}")
                            return@launch
                        }
                    }

                    // STEP 2: SEND IMAGE + CONTEXT TO AI
                    val currentContext = contextProvider()
                    _state.value = MonitoringState.Analyzing(startTimeMs = System.currentTimeMillis())

                    // STEP 3: AI PROCESSING
                    val result = visionAnalyzer.analyze(
                        bitmap = capturedBitmap,
                        context = currentContext
                    )
                    _latestResult.value = result
                    _analysisCount.value += 1

                    // STEP 4: START DELAY TIMER AFTER AI HAS FULLY FINISHED
                    val currentSettings = settingsProvider()
                    val delaySeconds = currentSettings.delaySeconds.coerceAtLeast(1)

                    for (remaining in delaySeconds downTo 1) {
                        if (!isActive) break
                        _state.value = MonitoringState.Waiting(
                            remainingSeconds = remaining,
                            totalSeconds = delaySeconds
                        )
                        delay(1000L)
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation when user presses Stop
                _state.value = MonitoringState.Idle
            } catch (e: Exception) {
                _state.value = MonitoringState.Error(e.localizedMessage ?: "Unexpected monitoring error")
            } finally {
                if (_state.value is MonitoringState.Stopping || _state.value is MonitoringState.Capturing || _state.value is MonitoringState.Analyzing || _state.value is MonitoringState.Waiting) {
                    _state.value = MonitoringState.Idle
                }
            }
        }
    }

    /**
     * Safely stops the monitoring loop, cancels active AI requests / delays,
     * and returns to Idle state.
     */
    fun stopMonitoring() {
        _state.value = MonitoringState.Stopping
        loopJob?.cancel()
        loopJob = null
        _state.value = MonitoringState.Idle
    }

    fun resetState() {
        stopMonitoring()
        _state.value = MonitoringState.Idle
    }
}
