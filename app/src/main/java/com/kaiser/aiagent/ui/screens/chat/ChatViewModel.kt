package com.kaiser.aiagent.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.aiagent.data.ai.AiMessage
import com.kaiser.aiagent.data.chat.ConversationEntity
import com.kaiser.aiagent.data.chat.ConversationRepository
import com.kaiser.aiagent.data.chat.MessageEntity
import com.kaiser.aiagent.data.chat.MessageRole
import com.kaiser.aiagent.domain.agent.AgentRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backs the Chat screen. Owns the active conversation and drives the
 * [AgentRuntime].
 *
 * State shape ([UiState]) intentionally mirrors what the screen needs
 * to render: the current conversation, the in-flight streaming text,
 * and a busy flag.
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

    init {
        // Reset agent state on init.
        agentRuntime.resetTurnCounters()
    }

    /**
     * Starts a new empty conversation and sets it as active.
     */
    fun startNewConversation() {
        if (_state.value.busy) return
        viewModelScope.launch {
            val conv = conversationRepo.createConversation()
            _state.value = UiState(conversation = conv, messages = emptyList())
        }
    }

    /**
     * Loads an existing conversation by id and sets it as active.
     */
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

    /**
     * Deletes the active conversation and clears the UI.
     */
    fun deleteActiveConversation() {
        if (_state.value.busy) return
        val id = _state.value.conversation?.id ?: return
        viewModelScope.launch {
            conversationRepo.deleteConversation(id)
            _state.value = UiState()
        }
    }

    /**
     * Sends a user message and runs the agent turn. The assistant's
     * response streams into [UiState.streamingText] and is committed
     * to the conversation when complete.
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        val conv = _state.value.conversation ?: run {
            // No active conversation — create one implicitly.
            viewModelScope.launch {
                val newConv = conversationRepo.createConversation()
                _state.value = _state.value.copy(conversation = newConv)
                sendMessage(text)  // re-enter with a conversation set
            }
            return
        }

        viewModelScope.launch {
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

            // 2. Build the AI history (all prior messages except the new one).
            val history: List<AiMessage> = (_state.value.conversation?.messages ?: emptyList())
                .map { AiMessage(role = it.role.toAiRole(), content = it.content) }

            // 3. Persist the user message.
            conversationRepo.appendMessage(conv.id, MessageRole.USER, trimmed)

            // 4. Run the agent turn.
            try {
                agentRuntime.runTurn(
                    history = history,
                    userMessage = trimmed,
                    onDelta = { delta ->
                        _state.value = _state.value.copy(
                            streamingText = _state.value.streamingText + delta
                        )
                    },
                    onFinal = { finalText ->
                        viewModelScope.launch {
                            // Persist the assistant message.
                            conversationRepo.appendMessage(
                                conv.id,
                                MessageRole.ASSISTANT,
                                finalText
                            )
                            // Auto-title if first exchange.
                            conversationRepo.maybeAutoTitle(conv.id)
                            // Refresh UI from the persisted conversation.
                            val refreshed = conversationRepo.get(conv.id)
                            _state.value = _state.value.copy(
                                conversation = refreshed,
                                messages = refreshed?.messages?.map { it.toUi() }
                                    ?: _state.value.messages,
                                streamingText = "",
                                busy = false
                            )
                        }.let { /* fire and forget */ }
                    }
                )
            } catch (t: Throwable) {
                Timber.w(t, "sendMessage failed")
                _state.value = _state.value.copy(
                    busy = false,
                    streamingText = "",
                    toast = "Error: ${t.message ?: t.javaClass.simpleName}"
                )
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

    /** Formats a timestamp as HH:mm for display. */
    fun formatTimestamp(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
}
