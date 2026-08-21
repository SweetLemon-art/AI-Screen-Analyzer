package com.example.localai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
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

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: Conversation? = null

    @Volatile
    private var loadedModel: LocalModel? = null

    override suspend fun load(model: LocalModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(lock) {
                if (conversation != null) {
                    throw IllegalStateException("Cannot load a model while local AI generation is running")
                }
                if (loadedModel?.id == model.id && engine?.isInitialized() == true) return@runCatching

                closeLocked()

                val modelFile = File(appContext.filesDir, "local_models/${model.id}/${model.fileName}")
                require(modelFile.isFile) { "Model file not found: ${model.fileName}" }

                val backend = when (model.accelerator) {
                    Accelerator.CPU -> Backend.CPU()
                    Accelerator.GPU -> Backend.GPU()
                    Accelerator.NPU -> Backend.NPU(appContext.applicationInfo.nativeLibraryDir)
                }

                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    cacheDir = appContext.cacheDir.absolutePath
                )

                val newEngine = Engine(config)
                try {
                    newEngine.initialize()
                } catch (error: Throwable) {
                    runCatching { newEngine.close() }
                    throw error
                }

                engine = newEngine
                loadedModel = model
            }
        }
    }

    override fun generate(prompt: String): Flow<LocalAiEvent> = flow {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        var setupError: Throwable? = null
        var activeConversation: Conversation? = null

        // Protect only the short-lived state transition. Never call emit() while
        // holding this monitor because emit is a suspension point.
        synchronized(lock) {
            val currentEngine = engine
            val currentModel = loadedModel

            when {
                currentEngine == null || currentModel == null -> {
                    setupError = IllegalStateException("No local model is loaded")
                }
                !currentEngine.isInitialized() -> {
                    setupError = IllegalStateException("Local model engine is not initialized")
                }
                conversation != null -> {
                    setupError = IllegalStateException("A local AI generation is already running")
                }
                else -> {
                    // LiteRT-LM 0.14.0 supports sampling configuration here.
                    // maxOutputToken is not part of the 0.14.0 ConversationConfig API,
                    // so do not pass it here; the engine/model default remains in charge
                    // of the output budget until the dependency is upgraded to an API
                    // that exposes a per-conversation output-token override.
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
                }
            }
        }

        setupError?.let {
            emit(LocalAiEvent.Failed(it))
            return@flow
        }

        val runningConversation = requireNotNull(activeConversation)

        emit(LocalAiEvent.Started)

        try {
            runningConversation.sendMessageAsync(prompt).collect { message ->
                emit(LocalAiEvent.Token(message.toString()))
            }
            emit(LocalAiEvent.Completed)
        } catch (error: Throwable) {
            emit(LocalAiEvent.Failed(error))
        } finally {
            synchronized(lock) {
                if (conversation === runningConversation) conversation = null
            }
            runCatching { runningConversation.close() }
        }
    }

    override suspend fun cancel(): Unit {
        withContext(Dispatchers.IO) {
            val activeConversation = synchronized(lock) {
                conversation
            } ?: return@withContext

            // The generate() flow owns the conversation lifetime. Cancel the native
            // operation here and let generate() close the conversation in finally.
            runCatching { activeConversation.cancelProcess() }
        }
    }

    override suspend fun unload(): Unit {
        withContext(Dispatchers.IO) {
            val activeConversation = synchronized(lock) { conversation }
            if (activeConversation != null) {
                runCatching { activeConversation.cancelProcess() }
            }

            synchronized(lock) {
                closeLocked()
            }
        }
    }

    private fun closeLocked() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        loadedModel = null
    }
}
