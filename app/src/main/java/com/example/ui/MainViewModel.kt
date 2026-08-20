package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AnalysisResult
import com.example.ai.ConnectionTestResult
import com.example.ai.GeminiModel
import com.example.ai.GeminiVisionAnalyzer
import com.example.ai.RateLimitState
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

    // Selected model state
    private val _selectedModel = MutableStateFlow(settingsRepository.loadSelectedModel())
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    // Discovered models list
    private val _discoveredModels = MutableStateFlow(settingsRepository.loadDiscoveredModels())
    val discoveredModels: StateFlow<List<GeminiModel>> = _discoveredModels.asStateFlow()

    // Explicit state indicating whether fresh model discovery has succeeded in current session
    private val _hasFreshModelDiscovery = MutableStateFlow(false)
    val hasFreshModelDiscovery: StateFlow<Boolean> = _hasFreshModelDiscovery.asStateFlow()

    // Model validation message (e.g. when selected model is no longer available)
    private val _modelValidationMessage = MutableStateFlow<String?>(null)
    val modelValidationMessage: StateFlow<String?> = _modelValidationMessage.asStateFlow()

    val visionAnalyzer = GeminiVisionAnalyzer(
        apiKeyStore = apiKeyStore,
        modelProvider = { _selectedModel.value },
        compatibleModelsProvider = { _discoveredModels.value }
    )
    val controller = MonitoringController(visionAnalyzer, viewModelScope)

    // Rate limit status
    val rateLimitState: StateFlow<RateLimitState> = visionAnalyzer.rateLimitState

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

    init {
        viewModelScope.launch {
            controller.latestResult.collect { result ->
                if (result != null && !result.isSuccess) {
                    if (result.summary == "MODEL_NOT_FOUND" || result.errorMessage?.contains("MODEL_NOT_FOUND") == true) {
                        clearSelectedModel()
                        _modelValidationMessage.value = "MODEL_NOT_FOUND: Selected model is not found on server."
                    }
                }
            }
        }
    }

    fun navigateTo(route: ScreenRoute) {
        _currentRoute.value = route
    }

    fun dismissModelValidationMessage() {
        _modelValidationMessage.value = null
    }

    fun selectModel(modelId: String) {
        val cleanModel = com.example.ai.normalizeModelId(modelId)
        if (cleanModel.isBlank()) {
            clearSelectedModel()
            return
        }
        val matched = _discoveredModels.value.find {
            it.canonicalModelId == cleanModel ||
            it.modelId == cleanModel ||
            com.example.ai.normalizeModelId(it.name) == cleanModel
        }
        if (matched != null) {
            val canonicalToSave = matched.canonicalModelId
            _selectedModel.value = canonicalToSave
            settingsRepository.saveSelectedModel(canonicalToSave)
            _modelValidationMessage.value = null
        } else {
            // P0-A: Model not in current compatible models: do NOT save, do NOT change selected model
            _modelValidationMessage.value = "MODEL_NOT_AVAILABLE: Selected model '$cleanModel' is not available in discovered compatible models."
        }
    }

    fun clearSelectedModel() {
        _selectedModel.value = ""
        settingsRepository.clearSelectedModel()
    }

    fun handleDiscoveredModels(freshModels: List<GeminiModel>) {
        // Filter compatible models: Must support generateContent
        // Deduplicate by canonicalModelId: prefer exact base model entry if available, else first stable entry
        val compatibleModels = freshModels
            .filter { it.supportedGenerationMethods.contains("generateContent") }
            .groupBy { it.canonicalModelId }
            .values
            .map { group ->
                group.find { it.modelId == it.canonicalModelId } ?: group.first()
            }

        _discoveredModels.value = compatibleModels
        settingsRepository.saveDiscoveredModels(compatibleModels)
        _hasFreshModelDiscovery.value = true

        if (compatibleModels.isEmpty()) {
            clearSelectedModel()
            _modelValidationMessage.value = "NO_COMPATIBLE_MODELS: No compatible models discovered."
            return
        }

        // Fresh discovery validation against persisted selection
        val persistedSelected = settingsRepository.loadSelectedModel()
        if (persistedSelected.isNotBlank()) {
            val matchingModel = compatibleModels.find {
                it.canonicalModelId == persistedSelected ||
                it.modelId == persistedSelected ||
                com.example.ai.normalizeModelId(it.name) == persistedSelected
            }
            if (matchingModel != null) {
                val canonical = matchingModel.canonicalModelId
                _selectedModel.value = canonical
                settingsRepository.saveSelectedModel(canonical)
                _modelValidationMessage.value = null
            } else {
                // Stale selection is cleared immediately without fallback auto-selection
                clearSelectedModel()
                _modelValidationMessage.value = "MODEL_NOT_AVAILABLE: Selected model '$persistedSelected' is no longer available."
            }
        } else {
            _selectedModel.value = ""
        }
    }

    fun fetchAvailableModels() {
        if (_isTestingConnection.value) return
        viewModelScope.launch {
            _isTestingConnection.value = true
            val result = visionAnalyzer.discoverModels()
            result.onSuccess { models ->
                handleDiscoveredModels(models)
            }.onFailure { error ->
                // Discovery failure preserves existing selection
                _modelValidationMessage.value = "Failed to refresh models: ${error.localizedMessage ?: "Network error"}"
            }
            _isTestingConnection.value = false
        }
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
        val safe = _settings.value.copy(delaySeconds = seconds.coerceIn(1, 600))
        _settings.value = safe
        settingsRepository.saveSettings(safe)
    }

    fun updateResolution(dimension: Int) {
        val safe = _settings.value.copy(maxResolutionDimension = dimension.coerceIn(480, 2160))
        _settings.value = safe
        settingsRepository.saveSettings(safe)
    }

    fun updateSettings(newSettings: CaptureSettings) {
        val safe = CaptureSettings.createSafe(
            delay = newSettings.delaySeconds,
            resolution = newSettings.maxResolutionDimension,
            quality = newSettings.compressionQuality
        )
        _settings.value = safe
        settingsRepository.saveSettings(safe)
    }

    fun saveApiKey(apiKey: String) {
        val saved = apiKeyStore.saveApiKey(apiKey)
        if (!saved) {
            _hasApiKey.value = false
            _maskedApiKey.value = ""
            _testResult.value = ConnectionTestResult.Error(
                "Failed to securely save the Gemini API key. Please try again."
            )
            return
        }

        _hasApiKey.value = apiKeyStore.hasApiKey()
        _maskedApiKey.value = apiKeyStore.getMaskedApiKey()
        _testResult.value = null
        // Automatically test connection and discover models
        testConnection()
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
            if (result is ConnectionTestResult.Success) {
                handleDiscoveredModels(result.models)
            }
            _isTestingConnection.value = false
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun onMediaProjectionApproved(resultCode: Int, data: Intent, appContext: Context) {
        viewModelScope.launch {
            if (!_hasFreshModelDiscovery.value) {
                _modelValidationMessage.value = "Refreshing model discovery before starting monitoring..."
                val discoveryResult = visionAnalyzer.discoverModels()
                discoveryResult.onSuccess { freshModels ->
                    handleDiscoveredModels(freshModels)
                }.onFailure { error ->
                    _modelValidationMessage.value = "DISCOVERY_FAILED: Cannot start monitoring. Model discovery failed: ${error.localizedMessage ?: "Network error"}"
                    return@launch
                }
            }

            if (!_hasFreshModelDiscovery.value) {
                _modelValidationMessage.value = "DISCOVERY_REQUIRED: Model discovery must complete before monitoring can start."
                return@launch
            }

            val currentSelected = _selectedModel.value
            val isValid = _discoveredModels.value.any { it.canonicalModelId == currentSelected }
            if (currentSelected.isBlank() || !isValid) {
                _modelValidationMessage.value = "MODEL_NOT_AVAILABLE: Please select a valid compatible model before starting monitoring."
                return@launch
            }

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
