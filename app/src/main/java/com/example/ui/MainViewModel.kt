package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiProviderRouter
import com.example.ai.AiProviderType
import com.example.ai.AnalysisResult
import com.example.ai.ConnectionTestResult
import com.example.ai.GeminiAiProvider
import com.example.ai.GeminiModel
import com.example.ai.GeminiVisionAnalyzer
import com.example.ai.LocalAiScreenProvider
import com.example.ai.RateLimitState
import com.example.capture.ScreenCaptureEngine
import com.example.capture.ScreenCaptureService
import com.example.data.AnalysisContext
import com.example.data.CaptureSettings
import com.example.data.SettingsRepository
import com.example.localai.LiteRtLmRuntime
import com.example.localai.LocalAiProvider
import com.example.localai.LocalModelRepository
import com.example.monitoring.MonitoringController
import com.example.monitoring.MonitoringState
import com.example.security.GeminiApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val apiKeyStore = GeminiApiKeyStore(application)
    private val settingsRepository = SettingsRepository(application)
    private val _selectedModel = MutableStateFlow(settingsRepository.loadSelectedModel())
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()
    private val _discoveredModels = MutableStateFlow(settingsRepository.loadDiscoveredModels())
    val discoveredModels: StateFlow<List<GeminiModel>> = _discoveredModels.asStateFlow()
    private val _hasFreshModelDiscovery = MutableStateFlow(false)
    val hasFreshModelDiscovery: StateFlow<Boolean> = _hasFreshModelDiscovery.asStateFlow()
    private val _modelValidationMessage = MutableStateFlow<String?>(null)
    val modelValidationMessage: StateFlow<String?> = _modelValidationMessage.asStateFlow()
    val visionAnalyzer = GeminiVisionAnalyzer(apiKeyStore, { _selectedModel.value }, { _discoveredModels.value })
    private val localModelRepository = LocalModelRepository(application)
    private val localAiProvider = LocalAiProvider(localModelRepository, LiteRtLmRuntime(application))
    private val aiProviderRouter = AiProviderRouter(
        listOf(GeminiAiProvider(visionAnalyzer), LocalAiScreenProvider(localAiProvider)),
        AiProviderType.GEMINI
    )
    private val _selectedAiProvider = MutableStateFlow(AiProviderType.GEMINI)
    val selectedAiProvider: StateFlow<AiProviderType> = _selectedAiProvider.asStateFlow()
    private val _askAiResult = MutableStateFlow<AnalysisResult?>(null)
    val askAiResult: StateFlow<AnalysisResult?> = _askAiResult.asStateFlow()
    private val _isAskingAi = MutableStateFlow(false)
    val isAskingAi: StateFlow<Boolean> = _isAskingAi.asStateFlow()
    private var askAiJob: Job? = null

    val controller = MonitoringController(visionAnalyzer, viewModelScope)
    val rateLimitState: StateFlow<RateLimitState> = visionAnalyzer.rateLimitState
    private val _savedContexts = MutableStateFlow(settingsRepository.loadContexts())
    val savedContexts: StateFlow<List<AnalysisContext>> = _savedContexts.asStateFlow()
    private val _currentContext = MutableStateFlow(
        _savedContexts.value.find { it.id == settingsRepository.loadSelectedContextId() }
            ?: _savedContexts.value.firstOrNull()
            ?: AnalysisContext.DEFAULT
    )
    val currentContext: StateFlow<AnalysisContext> = _currentContext.asStateFlow()
    private val _settings = MutableStateFlow(settingsRepository.loadSettings())
    val settings: StateFlow<CaptureSettings> = _settings.asStateFlow()
    private val _hasApiKey = MutableStateFlow(apiKeyStore.hasApiKey())
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()
    private val _maskedApiKey = MutableStateFlow(apiKeyStore.getMaskedApiKey())
    val maskedApiKey: StateFlow<String> = _maskedApiKey.asStateFlow()
    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()
    private val _testResult = MutableStateFlow<ConnectionTestResult?>(null)
    val testResult: StateFlow<ConnectionTestResult?> = _testResult.asStateFlow()
    val monitoringState: StateFlow<MonitoringState> = controller.state
    val latestBitmap = controller.latestBitmap
    val latestResult: StateFlow<AnalysisResult?> = controller.latestResult
    val analysisCount = controller.analysisCount
    val lastCaptureTimestamp = controller.lastCaptureTimestamp
    private val _currentRoute = MutableStateFlow(ScreenRoute.HOME)
    val currentRoute: StateFlow<ScreenRoute> = _currentRoute.asStateFlow()

    init {
        viewModelScope.launch {
            controller.latestResult.collect { result ->
                if (
                    result != null &&
                    !result.isSuccess &&
                    (result.summary == "MODEL_NOT_FOUND" || result.errorMessage?.contains("MODEL_NOT_FOUND") == true)
                ) {
                    clearSelectedModel()
                    _modelValidationMessage.value = "MODEL_NOT_FOUND: Selected model is not found on server."
                }
            }
        }
    }

    fun navigateTo(route: ScreenRoute) { _currentRoute.value = route }

    fun dismissModelValidationMessage() { _modelValidationMessage.value = null }

    fun selectAiProvider(type: AiProviderType) {
        aiProviderRouter.select(type).onSuccess { _selectedAiProvider.value = type }
    }

    fun askAi(question: String) {
        if (askAiJob?.isActive == true) return
        val prompt = question.trim()
        if (prompt.isBlank()) {
            _askAiResult.value = AnalysisResult(
                contextName = _currentContext.value.name,
                summary = "ASK_AI_ERROR",
                observations = listOf("Question must not be blank."),
                conclusion = "Enter a question before asking AI.",
                isSuccess = false,
                errorMessage = "Question must not be blank."
            )
            return
        }
        val bitmap = latestBitmap.value
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            _askAiResult.value = AnalysisResult(
                contextName = _currentContext.value.name,
                summary = "ASK_AI_ERROR",
                observations = listOf("No valid captured screen is available."),
                conclusion = "Start monitoring once to capture a screen before asking AI.",
                isSuccess = false,
                errorMessage = "No valid captured screen is available."
            )
            return
        }
        askAiJob = viewModelScope.launch {
            _isAskingAi.value = true
            try {
                if (_selectedAiProvider.value == AiProviderType.LOCAL) {
                    val localModel = localModelRepository.listModels().firstOrNull { it.capabilities.image }
                    if (localModel == null) {
                        _askAiResult.value = AnalysisResult(
                            contextName = _currentContext.value.name,
                            summary = "LOCAL_AI_ERROR",
                            observations = listOf("No imported local model with image capability is available."),
                            conclusion = "Import an image-capable LiteRT-LM model in Local AI first.",
                            isSuccess = false,
                            errorMessage = "No image-capable local model is available."
                        )
                        return@launch
                    }
                    val loadResult = localAiProvider.selectModel(localModel.id)
                    if (loadResult.isFailure) {
                        val message = loadResult.exceptionOrNull()?.message ?: "Failed to load local model."
                        _askAiResult.value = AnalysisResult(
                            contextName = _currentContext.value.name,
                            summary = "LOCAL_AI_ERROR",
                            observations = listOf(message),
                            conclusion = "Check the imported model and try again.",
                            isSuccess = false,
                            errorMessage = message
                        )
                        return@launch
                    }
                }
                _askAiResult.value = aiProviderRouter.analyze(
                    bitmap,
                    _currentContext.value,
                    _settings.value.copy(delaySeconds = 1),
                    prompt
                )
            } catch (_: CancellationException) {
                // Cancellation is control flow; preserve the previous result.
            } catch (error: Exception) {
                val message = error.localizedMessage ?: error.message ?: "AI request failed."
                _askAiResult.value = AnalysisResult(
                    contextName = _currentContext.value.name,
                    summary = "ASK_AI_ERROR",
                    observations = listOf(message),
                    conclusion = "Try again or switch AI providers.",
                    isSuccess = false,
                    errorMessage = message
                )
            } finally {
                _isAskingAi.value = false
                askAiJob = null
            }
        }
    }

    fun cancelAskAi() { askAiJob?.cancel() }

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
            _selectedModel.value = matched.canonicalModelId
            settingsRepository.saveSelectedModel(matched.canonicalModelId)
            _modelValidationMessage.value = null
        } else {
            _modelValidationMessage.value = "MODEL_NOT_AVAILABLE: Selected model '$cleanModel' is not available in discovered compatible models."
        }
    }

    fun clearSelectedModel() {
        _selectedModel.value = ""
        settingsRepository.clearSelectedModel()
    }

    fun handleDiscoveredModels(freshModels: List<GeminiModel>) {
        val compatibleModels = freshModels
            .filter { it.supportedGenerationMethods.contains("generateContent") }
            .groupBy { it.canonicalModelId }
            .values
            .map { group -> group.find { it.modelId == it.canonicalModelId } ?: group.first() }
        _discoveredModels.value = compatibleModels
        settingsRepository.saveDiscoveredModels(compatibleModels)
        _hasFreshModelDiscovery.value = true
        if (compatibleModels.isEmpty()) {
            clearSelectedModel()
            _modelValidationMessage.value = "NO_COMPATIBLE_MODELS: No compatible models discovered."
            return
        }
        val persistedSelected = settingsRepository.loadSelectedModel()
        if (persistedSelected.isNotBlank()) {
            val matchingModel = compatibleModels.find {
                it.canonicalModelId == persistedSelected ||
                    it.modelId == persistedSelected ||
                    com.example.ai.normalizeModelId(it.name) == persistedSelected
            }
            if (matchingModel != null) {
                _selectedModel.value = matchingModel.canonicalModelId
                settingsRepository.saveSelectedModel(matchingModel.canonicalModelId)
                _modelValidationMessage.value = null
            } else {
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
            visionAnalyzer.discoverModels()
                .onSuccess(::handleDiscoveredModels)
                .onFailure {
                    _modelValidationMessage.value = "Failed to refresh models: ${it.localizedMessage ?: "Network error"}"
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
            "custom_${System.currentTimeMillis()}",
            name.trim().ifBlank { "Custom Context" },
            instructions.trim().ifBlank { "Analyze what is visible on screen." },
            language.trim().ifBlank { "English" },
            false
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
            newSettings.delaySeconds,
            newSettings.maxResolutionDimension,
            newSettings.compressionQuality
        )
        _settings.value = safe
        settingsRepository.saveSettings(safe)
    }

    fun saveApiKey(apiKey: String) {
        val saved = apiKeyStore.saveApiKey(apiKey)
        if (!saved) {
            _hasApiKey.value = false
            _maskedApiKey.value = ""
            _testResult.value = ConnectionTestResult.Error("Failed to securely save the Gemini API key. Please try again.")
            return
        }
        _hasApiKey.value = apiKeyStore.hasApiKey()
        _maskedApiKey.value = apiKeyStore.getMaskedApiKey()
        _testResult.value = null
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
            if (result is ConnectionTestResult.Success) handleDiscoveredModels(result.models)
            _isTestingConnection.value = false
        }
    }

    fun clearTestResult() { _testResult.value = null }

    fun onMediaProjectionApproved(resultCode: Int, data: Intent, appContext: Context) {
        viewModelScope.launch {
            if (!_hasFreshModelDiscovery.value) {
                _modelValidationMessage.value = "Refreshing model discovery before starting monitoring..."
                visionAnalyzer.discoverModels()
                    .onSuccess(::handleDiscoveredModels)
                    .onFailure {
                        _modelValidationMessage.value = "DISCOVERY_FAILED: Cannot start monitoring. Model discovery failed: ${it.localizedMessage ?: "Network error"}"
                        return@launch
                    }
            }
            if (!_hasFreshModelDiscovery.value) {
                _modelValidationMessage.value = "DISCOVERY_REQUIRED: Model discovery must complete before monitoring can start."
                return@launch
            }
            val currentSelected = _selectedModel.value
            if (currentSelected.isBlank() || !_discoveredModels.value.any { it.canonicalModelId == currentSelected }) {
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
        askAiJob?.cancel()
        controller.stopMonitoring()
        ScreenCaptureEngine.stop()
        super.onCleared()
    }
}

enum class ScreenRoute(val title: String) {
    HOME("Home"),
    MONITOR("Monitor"),
    ASK_AI("Ask AI"),
    CONTEXT("Context"),
    SETTINGS("Settings"),
    LOCAL_AI("Local AI")
}
