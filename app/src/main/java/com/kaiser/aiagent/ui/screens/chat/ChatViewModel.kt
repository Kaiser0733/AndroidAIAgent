package com.kaiser.aiagent.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.aiagent.data.ai.AiMessage
import com.kaiser.aiagent.data.chat.ConversationEntity
import com.kaiser.aiagent.data.chat.ConversationRepository
import com.kaiser.aiagent.data.chat.MessageEntity
import com.kaiser.aiagent.data.chat.MessageRole
import com.kaiser.aiagent.domain.agent.AgentRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.7.11: Major reliability fix.
 *
 * Changes:
 *  - Stores the turn Job so it can be cancelled (Stop button).
 *  - Wraps sendMessage in try/finally so busy is ALWAYS cleared.
 *  - Eliminates the nested viewModelScope.launch inside onFinal
 *    (which could silently fail and leave busy=true forever).
 *  - stop() cancels the turn and resets all state immediately.
 */
class ChatViewModel(
    private val conversationRepo: ConversationRepository,
    private val agentRuntime: AgentRuntime
) : ViewModel() {

    data class UiMessage(
        val id: String,
        val role: MessageRole,
        val content: String,
        val timestamp: Long,
        val isStreaming: Boolean = false,
        val toolName: String? = null
    )

    data class UiState(
        val conversation: ConversationEntity? = null,
        val messages: List<UiMessage> = emptyList(),
        val streamingText: String = "",
        val busy: Boolean = false,
        val toast: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Live list of conversations (for the "new / switch" sheet). */
    val conversationsFlow = conversationRepo.conversations

    /** v0.7.11: the current turn job, so we can cancel it. */
    private var turnJob: Job? = null

    init {
        agentRuntime.resetTurnCounters()
    }

    fun startNewConversation() {
        if (_state.value.busy) return
        viewModelScope.launch {
            val conv = conversationRepo.createConversation()
            _state.value = UiState(conversation = conv, messages = emptyList())
        }
    }

    fun loadConversation(id: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            val conv = conversationRepo.get(id) ?: return@launch
            _state.value = UiState(
                conversation = conv,
                messages = conv.messages.map { it.toUi() }
            )
        }
    }

    fun deleteActiveConversation() {
        if (_state.value.busy) return
        val id = _state.value.conversation?.id ?: return
        viewModelScope.launch {
            conversationRepo.deleteConversation(id)
            _state.value = UiState()
        }
    }

    /**
     * v0.7.11: Stops the current turn immediately.
     * Cancels the job, clears busy, clears streaming text.
     */
    fun stop() {
        Timber.i("User tapped Stop — cancelling turn")
        turnJob?.cancel()
        turnJob = null
        agentRuntime.resetTurnCounters()
        _state.value = _state.value.copy(
            busy = false,
            streamingText = ""
        )
    }

    /**
     * Sends a user message and runs the agent turn.
     * v0.7.11: wrapped in try/finally so busy is ALWAYS cleared,
     * even if onFinal throws or the turn is cancelled.
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        val conv = _state.value.conversation ?: run {
            viewModelScope.launch {
                val newConv = conversationRepo.createConversation()
                _state.value = _state.value.copy(conversation = newConv)
                sendMessage(text)
            }
            return
        }

        // v0.7.11: store the job so stop() can cancel it.
        turnJob = viewModelScope.launch {
            // 1. Append the user message to the UI immediately.
            val userMsg = MessageEntity(
                id = java.util.UUID.randomUUID().toString(),
                role = MessageRole.USER,
                content = trimmed,
                createdAt = System.currentTimeMillis()
            )
            _state.value = _state.value.copy(
                messages = _state.value.messages + userMsg.toUi(),
                busy = true,
                streamingText = ""
            )

            // 2. Build the AI history.
            val history: List<AiMessage> = (_state.value.conversation?.messages ?: emptyList())
                .map { AiMessage(role = it.role.toAiRole(), content = it.content) }

            // 3. Persist the user message.
            conversationRepo.appendMessage(conv.id, MessageRole.USER, trimmed)

            // 4. Run the agent turn.
            var finalText: String? = null
            try {
                agentRuntime.runTurn(
                    history = history,
                    userMessage = trimmed,
                    conversationId = conv.id,
                    onDelta = { delta ->
                        _state.value = _state.value.copy(
                            streamingText = _state.value.streamingText + delta
                        )
                    },
                    onFinal = { text ->
                        // Just capture the text — persistence happens
                        // after runTurn returns (we need to be in a
                        // coroutine scope to call suspend functions).
                        finalText = text
                    }
                )
                // v0.7.11: persist the final text AFTER runTurn returns.
                // We're now back in the coroutine scope, so suspend calls work.
                if (finalText != null) {
                    try {
                        conversationRepo.appendMessage(conv.id, MessageRole.ASSISTANT, finalText!!)
                        conversationRepo.maybeAutoTitle(conv.id)
                        val refreshed = conversationRepo.get(conv.id)
                        _state.value = _state.value.copy(
                            conversation = refreshed,
                            messages = refreshed?.messages?.map { it.toUi() }
                                ?: _state.value.messages,
                            streamingText = "",
                            busy = false
                        )
                    } catch (e: Exception) {
                        Timber.w(e, "Persistence failed")
                        _state.value = _state.value.copy(
                            streamingText = "",
                            busy = false,
                            toast = "Error saving response: ${e.message}"
                        )
                    }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                Timber.i("Turn cancelled")
            } catch (t: Throwable) {
                Timber.w(t, "sendMessage failed")
                _state.value = _state.value.copy(
                    busy = false,
                    streamingText = "",
                    toast = "Error: ${t.message ?: t.javaClass.simpleName}"
                )
            } finally {
                turnJob = null
                if (_state.value.busy) {
                    _state.value = _state.value.copy(busy = false, streamingText = "")
                }
            }
        }
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }

    private fun MessageEntity.toUi(): UiMessage = UiMessage(
        id = id,
        role = role,
        content = content,
        timestamp = createdAt,
        toolName = toolName
    )

    fun formatTimestamp(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
}
