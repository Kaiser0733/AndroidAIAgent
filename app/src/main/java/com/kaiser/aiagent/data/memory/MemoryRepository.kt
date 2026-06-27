package com.kaiser.aiagent.data.memory

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
 * File-backed persistence for [MemoryEntry] objects.
 *
 * v0.3 layout: a single JSON file at `filesDir/memory/memories.json`
 * containing a flat array of [MemoryEntry]. The whole array is loaded
 * into memory on first access and rewritten on every mutation.
 *
 * This is intentionally simple — fine for hundreds of entries. A
 * future version should migrate to Room with FTS for full-text search
 * once memory volume grows.
 *
 * All public methods are safe to call from the main thread — they
 * dispatch to [Dispatchers.IO] internally.
 */
class MemoryRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    private val memoryDir: File by lazy {
        File(context.filesDir, "memory").apply { if (!exists()) mkdirs() }
    }

    private val memoryFile: File by lazy { File(memoryDir, "memories.json") }

    private val _entries = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val entries: StateFlow<List<MemoryEntry>> = _entries.asStateFlow()

    init {
        // Load synchronously on first construction (fast — file is small).
        loadFromDisk()
    }

    private fun loadFromDisk() {
        if (!memoryFile.exists()) {
            _entries.value = emptyList()
            return
        }
        try {
            val raw = memoryFile.readText()
            if (raw.isBlank()) {
                _entries.value = emptyList()
                return
            }
            val list = json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(MemoryEntry.serializer()),
                raw
            )
            _entries.value = list
        } catch (e: Exception) {
            Timber.w(e, "Failed to load memories; starting empty")
            _entries.value = emptyList()
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        try {
            val raw = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(MemoryEntry.serializer()),
                _entries.value
            )
            memoryFile.writeText(raw)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist memories")
        }
    }

    /** Adds a new memory entry and returns the created entry. */
    suspend fun add(
        type: String,
        content: String,
        source: String = "manual",
        tags: List<String> = emptyList()
    ): MemoryEntry {
        val entry = MemoryEntry(
            id = UUID.randomUUID().toString(),
            type = type,
            content = content,
            source = source,
            createdAt = System.currentTimeMillis(),
            tags = tags
        )
        _entries.value = _entries.value + entry
        persist()
        return entry
    }

    /** Returns the entry with the given id, or null. */
    fun get(id: String): MemoryEntry? = _entries.value.firstOrNull { it.id == id }

    /** Returns all entries of the given type. */
    fun byType(type: String): List<MemoryEntry> =
        _entries.value.filter { it.type == type }

    /**
     * v0.4: keyword search. Returns entries whose content, type, source,
     * or tags contain the [query] (case-insensitive). Empty query returns
     * all entries (capped at [limit]).
     *
     * NOT embeddings-based — just substring matching. Good enough for
     * short personal memory stores; v0.5+ can add semantic search.
     */
    fun search(query: String, limit: Int = 50): List<MemoryEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return _entries.value.take(limit)
        return _entries.value.filter { entry ->
            entry.content.lowercase().contains(q) ||
                entry.type.lowercase().contains(q) ||
                entry.source.lowercase().contains(q) ||
                entry.tags.any { it.lowercase().contains(q) }
        }.take(limit)
    }

    /** Deletes the entry with the given id. Returns true if deleted. */
    suspend fun delete(id: String): Boolean {
        val before = _entries.value.size
        _entries.value = _entries.value.filterNot { it.id == id }
        val changed = _entries.value.size != before
        if (changed) persist()
        return changed
    }

    /** Deletes all entries. Use with caution. */
    suspend fun clear() {
        _entries.value = emptyList()
        persist()
    }

    /** Total number of stored entries. */
    fun count(): Int = _entries.value.size
}
