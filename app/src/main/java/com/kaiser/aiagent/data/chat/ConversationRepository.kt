package com.kaiser.aiagent.data.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * File-backed persistence for [ConversationEntity] objects.
 *
 * v0.3 layout: one JSON file per conversation under
 * `filesDir/conversations/{id}.json`. The list of conversation IDs is
 * cached in memory; individual conversations are loaded lazily.
 *
 * Like [com.kaiser.aiagent.data.memory.MemoryRepository], this is
 * intentionally simple — fine for dozens of conversations with
 * hundreds of messages each. Migrate to Room for heavier use.
 */
class ConversationRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    private val dir: File by lazy {
        File(context.filesDir, "conversations").apply { if (!exists()) mkdirs() }
    }

    private val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val conversations: StateFlow<List<ConversationEntity>> = _conversations.asStateFlow()

    init {
        loadIndex()
    }

    private fun loadIndex() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        val list = files.mapNotNull { f ->
            try {
                json.decodeFromString(ConversationEntity.serializer(), f.readText())
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse conversation file %s", f.name)
                null
            }
        }.sortedByDescending { it.updatedAt }
        _conversations.value = list
    }

    /** Creates and persists a new empty conversation. */
    suspend fun createConversation(title: String = "New conversation"): ConversationEntity {
        val now = System.currentTimeMillis()
        val conv = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            messages = emptyList()
        )
        persist(conv)
        _conversations.value = (_conversations.value + conv).sortedByDescending { it.updatedAt }
        return conv
    }

    /** Returns the conversation with the given id, loading from disk if needed. */
    suspend fun get(id: String): ConversationEntity? = withContext(Dispatchers.IO) {
        val file = File(dir, "$id.json")
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString(ConversationEntity.serializer(), file.readText())
        } catch (e: Exception) {
            Timber.w(e, "Failed to load conversation %s", id)
            null
        }
    }

    /** Appends a message to an existing conversation. Returns the updated conversation. */
    suspend fun appendMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        isToolResult: Boolean = false,
        toolName: String? = null
    ): MessageEntity? = withContext(Dispatchers.IO) {
        val conv = get(conversationId) ?: return@withContext null
        val msg = MessageEntity(
            id = UUID.randomUUID().toString(),
            role = role,
            content = content,
            createdAt = System.currentTimeMillis(),
            isToolResult = isToolResult,
            toolName = toolName
        )
        val updated = conv.copy(
            messages = conv.messages + msg,
            updatedAt = System.currentTimeMillis()
        )
        persist(updated)
        _conversations.value = _conversations.value.map {
            if (it.id == conversationId) updated else it
        }.sortedByDescending { it.updatedAt }
        msg
    }

    /** Updates the title of an existing conversation. */
    suspend fun renameConversation(id: String, newTitle: String) {
        val conv = get(id) ?: return
        val updated = conv.copy(title = newTitle, updatedAt = System.currentTimeMillis())
        persist(updated)
        _conversations.value = _conversations.value.map {
            if (it.id == id) updated else it
        }.sortedByDescending { it.updatedAt }
    }

    /** Deletes a conversation and its file. */
    suspend fun deleteConversation(id: String) {
        val file = File(dir, "$id.json")
        if (file.exists()) file.delete()
        _conversations.value = _conversations.value.filterNot { it.id == id }
    }

    /**
     * Auto-titles a conversation based on its first user message.
     * Called after the first user → assistant exchange.
     */
    suspend fun maybeAutoTitle(id: String) {
        val conv = get(id) ?: return
        if (conv.title != "New conversation") return
        val firstUser = conv.messages.firstOrNull { it.role == MessageRole.USER } ?: return
        val newTitle = firstUser.content.take(40).replace("\n", " ").trim().ifBlank { "Untitled" }
        renameConversation(id, newTitle)
    }

    private fun persist(conv: ConversationEntity) {
        try {
            val file = File(dir, "${conv.id}.json")
            file.writeText(json.encodeToString(ConversationEntity.serializer(), conv))
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist conversation %s", conv.id)
        }
    }
}
