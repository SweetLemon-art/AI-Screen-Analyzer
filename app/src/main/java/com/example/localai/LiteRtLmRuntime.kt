package com.example.localai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/** LiteRT-LM implementation of the provider-neutral local model runtime. */
class LiteRtLmRuntime(context: Context) : LocalModelRuntime {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var loadedModel: LocalModel? = null
    @Volatile private var generationCompletion: CompletableDeferred<Unit>? = null

    override suspend fun load(model: LocalModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(lock) {
                if (conversation != null) throw IllegalStateException("Cannot load a model while local AI generation is running")
                if (loadedModel?.id == model.id && engine?.isInitialized() == true) return@runCatching
                closeLocked()
                val modelFile = File(appContext.filesDir, "local_models/${model.id}/${model.fileName}")
                require(modelFile.isFile) { "Model file not found: ${model.fileName}" }
                LocalModelValidator.validateFileName(model.fileName)
                LocalModelValidator.validateConfiguration(model.configuration)
                val config = buildEngineConfig(model, modelFile, appContext)
                val newEngine = Engine(config)
                try {
                    newEngine.initialize()
                } catch (error: Throwable) {
                    runCatching { newEngine.close() }
                    throw error
                }
                if (model.capabilities.image) {
                    try {
                        newEngine.createConversation(ConversationConfig()).close()
                    } catch (error: Throwable) {
                        runCatching { newEngine.close() }
                        throw IllegalStateException(
                            "Local model declares image capability but its vision runtime could not be initialized",
                            error
                        )
                    }
                }
                engine = newEngine
                loadedModel = model
            }
        }
    }

    override fun generate(prompt: String): Flow<LocalAiEvent> = generateInternal(prompt, null)
    override fun generate(prompt: String, imageBytes: ByteArray): Flow<LocalAiEvent> = generateInternal(prompt, imageBytes)

    private fun generateInternal(prompt: String, imageBytes: ByteArray?): Flow<LocalAiEvent> = flow {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        if (imageBytes != null) require(imageBytes.isNotEmpty()) { "Image bytes must not be empty" }
        var setupError: Throwable? = null
        var activeConversation: Conversation? = null
        val completion = CompletableDeferred<Unit>()
        synchronized(lock) {
            val currentEngine = engine
            val currentModel = loadedModel
            when {
                currentEngine == null || currentModel == null -> setupError = IllegalStateException("No local model is loaded")
                !currentEngine.isInitialized() -> setupError = IllegalStateException("Local model engine is not initialized")
                imageBytes != null && !currentModel.capabilities.image -> setupError = IllegalArgumentException("The selected local model does not declare image capability")
                conversation != null -> setupError = IllegalStateException("A local AI generation is already running")
                else -> {
                    val conversationConfig = ConversationConfig(
                        samplerConfig = SamplerConfig(
                            topK = currentModel.configuration.topK,
                            topP = currentModel.configuration.topP,
                            temperature = currentModel.configuration.temperature
                        )
                    )
                    val createdConversation = currentEngine.createConversation(conversationConfig)
                    activeConversation = createdConversation
                    conversation = createdConversation
                    generationCompletion = completion
                }
            }
        }
        setupError?.let {
            completion.complete(Unit)
            emit(LocalAiEvent.Failed(it))
            return@flow
        }
        val runningConversation = requireNotNull(activeConversation)
        emit(LocalAiEvent.Started)
        try {
            val responseFlow = if (imageBytes == null) {
                runningConversation.sendMessageAsync(prompt)
            } else {
                runningConversation.sendMessageAsync(Contents.of(Content.Text(prompt), Content.ImageBytes(imageBytes)))
            }
            responseFlow.collect { message -> emit(LocalAiEvent.Token(message.toString())) }
            emit(LocalAiEvent.Completed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emit(LocalAiEvent.Failed(error))
        } finally {
            synchronized(lock) {
                if (conversation === runningConversation) conversation = null
                if (generationCompletion === completion) generationCompletion = null
            }
            runCatching { runningConversation.close() }
            completion.complete(Unit)
        }
    }

    override suspend fun cancel(): Unit = withContext(Dispatchers.IO) {
        val activeConversation = synchronized(lock) { conversation } ?: return@withContext
        runCatching { activeConversation.cancelProcess() }
    }

    override suspend fun unload(): Unit = withContext(Dispatchers.IO) {
        val activeConversation: Conversation?
        val completion: CompletableDeferred<Unit>?
        synchronized(lock) {
            activeConversation = conversation
            completion = generationCompletion
        }
        if (activeConversation != null) {
            runCatching { activeConversation.cancelProcess() }
            completion?.await()
        }
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { conversation?.close() }
        conversation = null
        generationCompletion = null
        runCatching { engine?.close() }
        engine = null
        loadedModel = null
    }

    private companion object {
        fun buildEngineConfig(model: LocalModel, modelFile: File, context: Context): EngineConfig {
            val backend = when (model.accelerator) {
                Accelerator.CPU -> Backend.CPU()
                Accelerator.GPU -> Backend.GPU()
                Accelerator.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
            }
            val vision = imageRuntimeConfiguration(model.capabilities)
            return EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                visionBackend = vision.backend ?: null,
                maxNumImages = vision.maxNumImages,
                cacheDir = context.cacheDir.absolutePath
            )
        }

        internal fun imageRuntimeConfiguration(capabilities: ModelCapabilities): ImageRuntimeConfiguration =
            if (capabilities.image) {
                ImageRuntimeConfiguration(visionEnabled = true, maxNumImages = 1)
            } else {
                ImageRuntimeConfiguration(visionEnabled = false, maxNumImages = null)
            }
    }
}

data class ImageRuntimeConfiguration(
    val visionEnabled: Boolean,
    val maxNumImages: Int?
) {
    val backend: Backend?
        get() = null
}
