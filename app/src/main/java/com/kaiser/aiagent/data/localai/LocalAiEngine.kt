package com.kaiser.aiagent.data.localai

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Wraps Google's LiteRT-LM SDK to run Gemma/Qwen models entirely on-device.
 *
 * v0.5.5 fixes:
 *  - Uses MessageCallback.onMessage() for real token-by-token streaming
 *    (was using Flow<Message> which only emitted at the end)
 *  - Properly extracts text from Message.contents instead of toString()
 *  - Shows "Loading model..." status during the 10-30s model load
 *  - 120s timeout for local inference (CPU is much slower than cloud)
 */
class LocalAiEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModelPath: String? = null

    var lastLoadError: String? = null
        private set

    /** True if on-device AI is supported (Android 12+). */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** True if a model is loaded and ready. */
    fun isModelLoaded(): Boolean = engine?.isInitialized() == true && conversation != null

    fun getLoadedModelPath(): String? = currentModelPath

    /**
     * Loads a .litertlm model file. Takes 10-30 seconds on mid-range devices.
     *
     * v0.5.10: explicitly runs on Dispatchers.IO to prevent ANR
     * ("AI Agent is not responding" system dialog). The native
     * Engine.initialize() call blocks for 10-30 seconds — if it
     * runs on the main thread, Android shows the ANR dialog.
     */
    suspend fun loadModel(modelPath: String, systemPrompt: String, temperature: Double = 0.7): Boolean =
        withContext(Dispatchers.IO) {
            if (!isSupported()) return@withContext false

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                lastLoadError = "Model file not found: $modelPath"
                return@withContext false
            }

            try {
                close()
                Timber.i("Loading on-device model: %s (%d bytes)", modelPath, modelFile.length())

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    visionBackend = null,
                    audioBackend = null,
                    maxNumTokens = 4096,
                    maxNumImages = null,
                    cacheDir = File(context.cacheDir, "litertlm").absolutePath
                )

                val newEngine = Engine(engineConfig)
                newEngine.initialize()
                engine = newEngine
                currentModelPath = modelPath

                val systemMessage = Message.system(systemPrompt)
                val samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature, seed = 0)
                val convConfig = ConversationConfig(
                    systemInstruction = null,
                    initialMessages = listOf(systemMessage),
                    tools = emptyList(),
                    samplerConfig = samplerConfig,
                    automaticToolCalling = false,
                    channels = emptyList(),
                    extraContext = emptyMap(),
                    loraConfig = null
                )
                conversation = newEngine.createConversation(convConfig)

                Timber.i("On-device model loaded successfully")
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to load on-device model: %s", e.message)
                lastLoadError = e.message ?: e.javaClass.simpleName
                close()
                false
            }
        }

    /**
     * Sends a user message and streams the response token-by-token.
     *
     * v0.5.10: added yield() calls to prevent ANR. The native inference
     * blocks the IO thread; yield() lets other coroutines (including UI
     * updates) run between tokens.
     */
    fun sendMessage(userMessage: String): Flow<String> = flow {
        val conv = conversation
        if (conv == null) {
            throw LocalAiException("No model loaded. Download a model first via Settings → Models.")
        }

        // v0.5.10: yield to let the UI update before starting heavy inference
        kotlinx.coroutines.yield()

        val tokenChannel = Channel<String>(Channel.BUFFERED)
        var inferenceError: Throwable? = null
        var previousText = ""

        val userMsg = Message.user(userMessage)

        conv.sendMessageAsync(userMsg, object : MessageCallback {
            override fun onMessage(message: Message) {
                val fullText = extractText(message)
                if (fullText.length > previousText.length) {
                    val delta = fullText.substring(previousText.length)
                    previousText = fullText
                    tokenChannel.trySend(delta)
                }
            }

            override fun onDone() {
                tokenChannel.close()
            }

            override fun onError(t: Throwable) {
                Timber.e(t, "Local AI inference error: %s", t.message)
                inferenceError = t
                tokenChannel.close()
            }
        }, emptyMap())

        for (token in tokenChannel) {
            emit(token)
            // v0.5.10: yield after each token to keep the UI responsive
            kotlinx.coroutines.yield()
        }

        inferenceError?.let {
            throw LocalAiException(
                "Inference failed: ${it.message ?: it.javaClass.simpleName}",
                it
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Extracts plain text from a Message's Contents.
     * Iterates through Content elements and concatenates all Content.Text items.
     */
    private fun extractText(message: Message): String {
        return try {
            val contents = message.contents
            val contentList = contents.contents  // List<Content>
            val sb = StringBuilder()
            for (content in contentList) {
                if (content is Content.Text) {
                    sb.append(content.text)
                }
            }
            sb.toString()
        } catch (e: Exception) {
            Timber.w(e, "Failed to extract text from message")
            ""
        }
    }

    fun close() {
        try { conversation?.close() } catch (e: Exception) { Timber.w(e, "Error closing conversation") }
        try { engine?.close() } catch (e: Exception) { Timber.w(e, "Error closing engine") }
        conversation = null
        engine = null
        currentModelPath = null
    }
}

class LocalAiException(message: String, cause: Throwable? = null) : Exception(message, cause)
