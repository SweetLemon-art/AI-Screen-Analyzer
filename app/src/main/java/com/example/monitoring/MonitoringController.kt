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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
                    // Coordinator survives exceptions and remains ready for subsequent commands
                }
            }
        }
    }

    private suspend fun handleLifecycleCommand(command: LifecycleCommand) {
        when (command) {
            is LifecycleCommand.Start -> {
                // 1. Invalidate previous session
                val newSessionId = sessionCounter.incrementAndGet()

                // 2. Stop/cancel any existing monitoring Job & await completion
                activeJob?.cancel()
                try {
                    activeJob?.join()
                } catch (ignored: Exception) {}
                activeJob = null

                // 3. Verify that this start session is still the latest (no intervening STOP)
                if (newSessionId != sessionCounter.get()) {
                    return
                }

                // 4. Update state to starting
                _state.value = MonitoringState.Starting

                // 5. Create exactly ONE monitoring Job
                val job = coroutineScope.launch {
                    runMonitoringLoop(newSessionId, command.contextProvider, command.settingsProvider)
                }
                activeJob = job
            }

            is LifecycleCommand.Stop -> {
                // 1. Invalidate session immediately
                sessionCounter.incrementAndGet()

                // 2. Set Idle immediately
                _state.value = MonitoringState.Idle

                // 3. Cancel active monitoring Job & await completion
                activeJob?.cancel()
                try {
                    activeJob?.join()
                } catch (ignored: Exception) {}
                activeJob = null

                // 4. Ensure Idle state is published
                _state.value = MonitoringState.Idle
            }
        }
    }

    /**
     * Enqueues START to the single lifecycle coordinator.
     */
    fun startMonitoring(
        contextProvider: () -> AnalysisContext,
        settingsProvider: () -> CaptureSettings
    ) {
        commandChannel.trySend(LifecycleCommand.Start(contextProvider, settingsProvider))
    }

    /**
     * Enqueues STOP to the single lifecycle coordinator.
     */
    fun stopMonitoring() {
        val result = commandChannel.trySend(LifecycleCommand.Stop)
        if (result.isFailure) {
            // Channel is closed/unavailable: enforce cancellation and cleanup directly
            sessionCounter.incrementAndGet()
            activeJob?.cancel()
            _state.value = MonitoringState.Idle
        }
    }

    /**
     * Sequential execution of Capture -> AI -> Delay.
     * Uses [currentCoroutineContext().isActive] for coroutine-local cancellation state.
     * Verifies [sessionId == sessionCounter.get()] before every action and state emission.
     */
    private suspend fun runMonitoringLoop(
        sessionId: Long,
        contextProvider: () -> AnalysisContext,
        settingsProvider: () -> CaptureSettings
    ) {
        try {
            // Wait for capture provider to become ready
            var waitAttempts = 0
            while (!captureProvider.isReady.value && waitAttempts < 25 && currentCoroutineContext().isActive) {
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

            while (currentCoroutineContext().isActive && sessionId == sessionCounter.get()) {
                // 1. CAPTURE SCREEN
                if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return
                _state.value = MonitoringState.Capturing

                val captureResult = captureProvider.captureSingleFrame()
                if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return

                val capturedBitmap = when (captureResult) {
                    is CaptureResult.Success -> {
                        if (sessionId == sessionCounter.get()) {
                            _latestBitmap.value = captureResult.bitmap
                            _lastCaptureTimestamp.value = System.currentTimeMillis()
                        }
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
                if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return
                val currentContext = contextProvider()
                val currentSettings = settingsProvider()
                _state.value = MonitoringState.Analyzing(startTimeMs = System.currentTimeMillis())

                // 3. AI PROCESSING (Wait for complete response)
                val result = visionAnalyzer.analyze(
                    bitmap = capturedBitmap,
                    context = currentContext,
                    settings = currentSettings
                )

                if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return
                _latestResult.value = result
                _analysisCount.value += 1

                // 4. DELAY TIMER (Strictly starts AFTER AI completes)
                val delaySeconds = currentSettings.delaySeconds.coerceIn(1, 600)
                for (remaining in delaySeconds downTo 1) {
                    if (sessionId != sessionCounter.get() || !currentCoroutineContext().isActive) return
                    _state.value = MonitoringState.Waiting(
                        remainingSeconds = remaining,
                        totalSeconds = delaySeconds
                    )
                    delay(1000L)
                }
            }
        } catch (e: CancellationException) {
            // CancellationException must propagate and never be swallowed
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
}
