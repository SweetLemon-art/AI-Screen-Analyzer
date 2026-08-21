package com.example.localai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File

/** LiteRT-LM implementation of the provider-neutral local model runtime. */
class LiteRtLmRuntime(private val context: Context) : LocalModelRuntime {
    private val lock = Any()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    @Volatile
    private var loadedModelId: String? = null

    override suspend fun load(model: LocalModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(lock) {
                if (loadedModelId == model.id && engine?.isInitialized() == true) return@runCatching

                closeLocked()

                val modelFile = File(context.filesDir, "local_models/${model.id}/${model.fileName}")
                require(modelFile.isFile) { "Model file not found: ${model.fileName}" }

                val backend = when (model.accelerator) {
                    Accelerator.CPU -> Backend.CPU()
                    Accelerator.GPU -> Backend.GPU()
                    Accelerator.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
                }

                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    maxNumTokens = model.configuration.maxTokens,
                    cacheDir = context.cacheDir.absolutePath
                )

                val newEngine = Engine(config)
                try {
                    newEngine.initialize()
                } catch (error: Throwable) {
                    runCatching { newEngine.close() }
                    throw error
                }

                engine = newEngine
                loadedModelId = model.id
            }
        }
    }

    override fun generate(prompt: String): Flow<LocalAiEvent> = flow {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        val activeEngine = engine ?: run {
            emit(LocalAiEvent.Failed(IllegalStateException("No local model is loaded")))
            return@flow
        }
        require(activeEngine.isInitialized()) { "Local model engine is not initialized" }

        val config = synchronized(lock) {
            val modelId = loadedModelId ?: error("No local model is loaded")
            modelId to activeEngine.engineConfig
        }

        val conversationConfig = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = config.second.maxNumTokens?.coerceAtLeast(1) ?: 64,
                topP = 0.95,
                temperature = 1.0
            )
        )

        emit(LocalAiEvent.Started)

        val activeConversation = activeEngine.createConversation(conversationConfig)
        synchronized(lock) { conversation = activeConversation }

        try {
            activeConversation.sendMessageAsync(prompt).collect { message ->
                emit(LocalAiEvent.Token(message.toString()))
            }
            emit(LocalAiEvent.Completed)
        } catch (error: Throwable) {
            emit(LocalAiEvent.Failed(error))
        } finally {
            synchronized(lock) {
                if (conversation === activeConversation) conversation = null
            }
            runCatching { activeConversation.close() }
        }
    }

    override suspend fun cancel() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val activeConversation = conversation ?: return@synchronized
            runCatching { activeConversation.cancelProcess() }
            runCatching { activeConversation.close() }
            conversation = null
        }
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        loadedModelId = null
    }
}
