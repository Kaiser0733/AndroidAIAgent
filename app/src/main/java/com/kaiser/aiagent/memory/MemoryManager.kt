package com.kaiser.aiagent.memory

import com.kaiser.aiagent.data.memory.MemoryEntry
import com.kaiser.aiagent.data.memory.MemoryRepository

/**
 * Higher-level façade over [MemoryRepository]. The repository is the
 * persistence layer; the manager is the policy layer — it knows about
 * memory *types*, validation rules, and (in future versions) automatic
 * extraction from chat history.
 *
 * v0.3 implements only manual CRUD. Future versions will add:
 *   - extractFromConversation() — auto-generate memories from chat
 *   - recall(query) — semantic search via embeddings
 *   - prune() — drop stale or low-salience entries
 */
class MemoryManager(private val repository: MemoryRepository) {

    /** Live stream of all stored memories. */
    val entries get() = repository.entries

    /** Total count (for the Debug screen). */
    fun count(): Int = repository.count()

    /**
     * Saves a manual memory entry. Validates that content is non-blank
     * and type is one of the supported categories.
     */
    suspend fun save(
        type: MemoryType,
        content: String,
        tags: List<String> = emptyList()
    ): MemoryEntry? {
        if (content.isBlank()) return null
        return repository.add(
            type = type.value,
            content = content.trim(),
            source = "manual",
            tags = tags
        )
    }

    suspend fun delete(id: String): Boolean = repository.delete(id)

    suspend fun clear() = repository.clear()

    /** Supported memory categories for v0.3. */
    enum class MemoryType(val value: String) {
        FACT("fact"),
        PREFERENCE("preference"),
        NOTE("note")
    }
}
