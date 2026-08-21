package com.example.localai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
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
class LiteRtLmRuntime(private val context: Context) : LocalModelRuntime {
    private val lock = Any()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    @Volatile
    private var loadedModel: LocalModel? = null

    override suspend fun load(model: LocalModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(lock) {
                if (loadedModel?.id == model.id && engine?.isInitialized() == true) return@runCatching

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
                loadedModel = model
            }
        }
    }

    override fun generate(prompt: String): Flow<LocalAiEvent> = flow {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        val activeEngine = engine ?: run {
            emit(LocalAiEvent.Failed(IllegalStateException("No local model is loaded")))
            return@flow
        }
        val model = loadedModel ?: run {
            emit(LocalAiEvent.Failed(IllegalStateException("No local model is loaded")))
            return@flow
        }
        require(activeEngine.isInitialized()) { "Local model engine is not initialized" }

        val conversationConfig = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = model.configuration.topK,
                topP = model.configuration.topP,
                temperature = model.configuration.temperature
            )
        )

        emit(LocalAiEvent.Started)

        val activeConversation = activeEngine.createConversation(conversationConfig)
        synchronized(lock) { conversation = activeConversation }

        try {
            activeConversation.sendMessageAsync(
                prompt,
                maxOutputToken = model.configuration.maxTokens
            ).collect { message ->
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
        loadedModel = null
    }
}
