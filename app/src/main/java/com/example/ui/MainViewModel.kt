package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AnalysisResult
import com.example.ai.GeminiVisionAnalyzer
import com.example.capture.ScreenCaptureEngine
import com.example.capture.ScreenCaptureService
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.monitoring.MonitoringController
import com.example.monitoring.MonitoringState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val visionAnalyzer = GeminiVisionAnalyzer()
    val controller = MonitoringController(visionAnalyzer, viewModelScope)

    // Active analysis context
    private val _currentContext = MutableStateFlow(AnalysisContext.DEFAULT)
    val currentContext: StateFlow<AnalysisContext> = _currentContext.asStateFlow()

    // Available contexts (presets + user custom)
    private val _savedContexts = MutableStateFlow(AnalysisContext.DEFAULT_PRESETS)
    val savedContexts: StateFlow<List<AnalysisContext>> = _savedContexts.asStateFlow()

    // Capture and delay settings
    private val _settings = MutableStateFlow(CaptureSettings.DEFAULT)
    val settings: StateFlow<CaptureSettings> = _settings.asStateFlow()

    // Expose monitoring state flows directly
    val monitoringState: StateFlow<MonitoringState> = controller.state
    val latestBitmap = controller.latestBitmap
    val latestResult: StateFlow<AnalysisResult?> = controller.latestResult
    val analysisCount = controller.analysisCount
    val lastCaptureTimestamp = controller.lastCaptureTimestamp

    // UI Navigation Route
    private val _currentRoute = MutableStateFlow(ScreenRoute.HOME)
    val currentRoute: StateFlow<ScreenRoute> = _currentRoute.asStateFlow()

    fun navigateTo(route: ScreenRoute) {
        _currentRoute.value = route
    }

    fun selectContext(context: AnalysisContext) {
        _currentContext.value = context
    }

    fun saveAndSelectContext(name: String, instructions: String, language: String) {
        val newContext = AnalysisContext(
            id = "custom_${System.currentTimeMillis()}",
            name = name.trim().ifBlank { "Custom Analysis" },
            instructions = instructions.trim().ifBlank { "Analyze what is visible on screen." },
            language = language.trim().ifBlank { "English" },
            isPreset = false
        )
        _savedContexts.value = listOf(newContext) + _savedContexts.value.filter { it.id != newContext.id }
        _currentContext.value = newContext
    }

    fun updateDelay(seconds: Int) {
        _settings.value = _settings.value.copy(delaySeconds = seconds.coerceIn(1, 300))
    }

    fun updateSettings(settings: CaptureSettings) {
        _settings.value = settings
    }

    fun onMediaProjectionApproved(resultCode: Int, data: Intent, appContext: Context) {
        viewModelScope.launch {
            // 1. Start foreground service
            ScreenCaptureService.startService(appContext, resultCode, data)

            // 2. Start monitoring loop in controller
            controller.startMonitoring(
                contextProvider = { _currentContext.value },
                settingsProvider = { _settings.value }
            )

            // 3. Switch view to MonitorScreen so user immediately sees live activity
            _currentRoute.value = ScreenRoute.MONITOR
        }
    }

    fun stopMonitoring(appContext: Context) {
        controller.stopMonitoring()
        ScreenCaptureService.stopService(appContext)
        ScreenCaptureEngine.stop()
    }

    override fun onCleared() {
        controller.stopMonitoring()
        ScreenCaptureEngine.stop()
        super.onCleared()
    }
}

enum class ScreenRoute(val title: String) {
    HOME("Home"),
    MONITOR("Monitor"),
    CONTEXT("Context"),
    SETTINGS("Settings")
}
