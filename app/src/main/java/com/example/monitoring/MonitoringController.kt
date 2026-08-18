package com.example.monitoring

import android.graphics.Bitmap
import com.example.ai.AnalysisResult
import com.example.ai.VisionAnalyzer
import com.example.capture.CaptureResult
import com.example.capture.ScreenCaptureEngine
import com.example.capture.ScreenCaptureProvider
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
     * - Shared lifecycle mutex protects only references/state during transitions.
     * - lifecycleMutex is NEVER held during monitoring loop or while waiting for activeJob.join().
     * - Old jobs are cancelled and joined BEFORE a new session loop begins.
     * - Obsolete / cancelled sessions cannot publish state, results, bitmaps, or errors.
     * - Delay strictly runs AFTER the AI analysis finishes.
     */
    fun startMonitoring(
        contextProvider: () -> AnalysisContext,
        settingsProvider: () -> CaptureSettings
    ) {
        val targetSession = sessionCounter.incrementAndGet()
        _state.value = MonitoringState.Starting
        coroutineScope.launch {
            // STEP 1: Acquire mutex to check session and grab old job
            val jobToCancel: Job? = lifecycleMutex.withLock {
                if (targetSession != sessionCounter.get()) {
                    return@launch
                }
                val prev = activeJob
                activeJob = null
                prev
            }

            // STEP 2: Cancel and join old job WITHOUT holding lifecycleMutex
            if (jobToCancel != null) {
                jobToCancel.cancel()
                try {
                    jobToCancel.join()
                } catch (ignored: Exception) {}
            }

            // STEP 3: Reacquire mutex, verify session is still current, create and store new job
            lifecycleMutex.withLock {
                if (targetSession != sessionCounter.get()) {
                    return@launch
                }

                _state.value = MonitoringState.Starting

                val newJob = coroutineScope.launch {
                    runMonitoringLoop(targetSession, contextProvider, settingsProvider)
                }
                activeJob = newJob
            }
        }
    }

    /**
     * Safely and idempotently stops the monitoring loop.
     * Guaranteed to invalidate pending starts, cancel and join the active job without holding lifecycleMutex.
     */
    fun stopMonitoring() {
        val targetSession = sessionCounter.incrementAndGet()
        _state.value = MonitoringState.Idle
        coroutineScope.launch {
            // STEP 1: Atomically grab old job and clear activeJob under mutex
            val jobToCancel: Job? = lifecycleMutex.withLock {
                val prev = activeJob
                activeJob = null
                prev
            }

            // STEP 2: Cancel and join old job WITHOUT holding lifecycleMutex
            if (jobToCancel != null) {
                jobToCancel.cancel()
                try {
                    jobToCancel.join()
                } catch (ignored: Exception) {}
            }

            // STEP 3: Verify and ensure Idle state
            lifecycleMutex.withLock {
                if (targetSession == sessionCounter.get()) {
                    _state.value = MonitoringState.Idle
                }
            }
        }
    }

    private suspend fun runMonitoringLoop(
        sessionId: Long,
        contextProvider: () -> AnalysisContext,
        settingsProvider: () -> CaptureSettings
    ) {
        try {
            // Wait for capture provider to become ready
            var waitAttempts = 0
            while (!captureProvider.isReady.value && waitAttempts < 25 && coroutineScope.isActive) {
                if (sessionId != sessionCounter.get()) return
                delay(100)
                waitAttempts++
            }

            if (!captureProvider.isReady.value) {
                if (sessionId == sessionCounter.get()) {
                    _state.value = MonitoringState.Error("Screen capture session is not active. Please start monitoring again.")
                }
                return
            }

            while (coroutineScope.isActive && sessionId == sessionCounter.get()) {
                // 1. CAPTURE SCREEN
                if (sessionId != sessionCounter.get()) return
                _state.value = MonitoringState.Capturing

                val captureResult = captureProvider.captureSingleFrame()
                if (sessionId != sessionCounter.get()) return

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
                        return
                    }
                }

                // 2. PREPARE CONTEXT & SETTINGS
                if (sessionId != sessionCounter.get()) return
                val currentContext = contextProvider()
                val currentSettings = settingsProvider()
                _state.value = MonitoringState.Analyzing(startTimeMs = System.currentTimeMillis())

                // 3. AI PROCESSING (Wait for complete response)
                val result = visionAnalyzer.analyze(
                    bitmap = capturedBitmap,
                    context = currentContext,
                    settings = currentSettings
                )

                if (sessionId != sessionCounter.get()) return
                _latestResult.value = result
                _analysisCount.value += 1

                // 4. DELAY TIMER (Strictly starts AFTER AI completes)
                val delaySeconds = currentSettings.delaySeconds.coerceIn(1, 600)
                for (remaining in delaySeconds downTo 1) {
                    if (sessionId != sessionCounter.get()) return
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

    fun resetState() {
        stopMonitoring()
        _latestResult.value = null
        _analysisCount.value = 0
        _lastCaptureTimestamp.value = null
    }
}
