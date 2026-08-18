package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AnalysisResult
import com.example.ai.ConnectionTestResult
import com.example.ai.GeminiVisionAnalyzer
import com.example.capture.ScreenCaptureEngine
import com.example.capture.ScreenCaptureService
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.data.SettingsRepository
import com.example.monitoring.MonitoringController
import com.example.monitoring.MonitoringState
import com.example.security.GeminiApiKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val apiKeyStore = GeminiApiKeyStore(application)
    private val settingsRepository = SettingsRepository(application)
    val visionAnalyzer = GeminiVisionAnalyzer(apiKeyStore)
    val controller = MonitoringController(visionAnalyzer, viewModelScope)

    // Saved contexts & selected context
    private val _savedContexts = MutableStateFlow(settingsRepository.loadContexts())
    val savedContexts: StateFlow<List<AnalysisContext>> = _savedContexts.asStateFlow()

    private val _currentContext = MutableStateFlow(
        _savedContexts.value.find { it.id == settingsRepository.loadSelectedContextId() }
            ?: _savedContexts.value.firstOrNull()
            ?: AnalysisContext.DEFAULT
    )
    val currentContext: StateFlow<AnalysisContext> = _currentContext.asStateFlow()

    // Capture settings
    private val _settings = MutableStateFlow(settingsRepository.loadSettings())
    val settings: StateFlow<CaptureSettings> = _settings.asStateFlow()

    // API Key status StateFlow to trigger reactive recompositions in SettingsScreen
    private val _hasApiKey = MutableStateFlow(apiKeyStore.hasApiKey())
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    private val _maskedApiKey = MutableStateFlow(apiKeyStore.getMaskedApiKey())
    val maskedApiKey: StateFlow<String> = _maskedApiKey.asStateFlow()

    // Connection testing state
    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _testResult = MutableStateFlow<ConnectionTestResult?>(null)
    val testResult: StateFlow<ConnectionTestResult?> = _testResult.asStateFlow()

    // Monitoring State flows
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
        settingsRepository.saveSelectedContextId(context.id)
    }

    fun saveAndSelectContext(name: String, instructions: String, language: String) {
        val newContext = AnalysisContext(
            id = "custom_${System.currentTimeMillis()}",
            name = name.trim().ifBlank { "Custom Context" },
            instructions = instructions.trim().ifBlank { "Analyze what is visible on screen." },
            language = language.trim().ifBlank { "English" },
            isPreset = false
        )
        val updated = listOf(newContext) + _savedContexts.value.filter { it.id != newContext.id }
        _savedContexts.value = updated
        settingsRepository.saveContexts(updated)
        selectContext(newContext)
    }

    fun updateDelay(seconds: Int) {
        val clamped = seconds.coerceIn(1, 600)
        val updated = _settings.value.copy(delaySeconds = clamped)
        _settings.value = updated
        settingsRepository.saveSettings(updated)
    }

    fun updateResolution(dimension: Int) {
        val updated = _settings.value.copy(maxResolutionDimension = dimension)
        _settings.value = updated
        settingsRepository.saveSettings(updated)
    }

    fun updateSettings(newSettings: CaptureSettings) {
        _settings.value = newSettings
        settingsRepository.saveSettings(newSettings)
    }

    fun saveApiKey(apiKey: String) {
        apiKeyStore.saveApiKey(apiKey)
        _hasApiKey.value = apiKeyStore.hasApiKey()
        _maskedApiKey.value = apiKeyStore.getMaskedApiKey()
        _testResult.value = null
    }

    fun clearApiKey() {
        apiKeyStore.clearApiKey()
        _hasApiKey.value = false
        _maskedApiKey.value = ""
        _testResult.value = null
    }

    fun testConnection() {
        if (_isTestingConnection.value) return
        viewModelScope.launch {
            _isTestingConnection.value = true
            _testResult.value = null
            val result = visionAnalyzer.testConnection()
            _testResult.value = result
            _isTestingConnection.value = false
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun onMediaProjectionApproved(resultCode: Int, data: Intent, appContext: Context) {
        viewModelScope.launch {
            ScreenCaptureService.startService(appContext, resultCode, data)
            controller.startMonitoring(
                contextProvider = { _currentContext.value },
                settingsProvider = { _settings.value }
            )
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
