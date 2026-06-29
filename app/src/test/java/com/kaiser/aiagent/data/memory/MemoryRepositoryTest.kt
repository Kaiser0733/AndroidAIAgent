package com.kaiser.aiagent.data.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [MemoryRepository]. Uses Robolectric to provide a real
 * Android Context so the DataStore-like file operations actually run.
 *
 * Covers:
 *  - add() creates a new entry with a UUID and persists it
 *  - get() returns the entry by id
 *  - byType() filters by type
 *  - search() returns entries matching the query (case-insensitive)
 *  - search() with empty query returns all entries (capped)
 *  - delete() removes the entry
 *  - clear() empties the store
 *  - count() returns the correct size
 *  - entries StateFlow updates after mutations
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MemoryRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: MemoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear any previous state.
        File(context.filesDir, "memory").deleteRecursively()
        repo = MemoryRepository(context)
    }

    @After
    fun tearDown() {
        File(context.filesDir, "memory").deleteRecursively()
    }

    @Test
    fun `add creates a new entry with a UUID and persists it`() = runBlocking {
        val entry = repo.add(type = "fact", content = "The sky is blue", source = "test")
        assertThat(entry.id).isNotEmpty()
        assertThat(entry.type).isEqualTo("fact")
        assertThat(entry.content).isEqualTo("The sky is blue")
        assertThat(repo.count()).isEqualTo(1)
        // Reload from disk to verify persistence.
        val reloaded = MemoryRepository(context)
        assertThat(reloaded.count()).isEqualTo(1)
        assertThat(reloaded.get(entry.id)?.content).isEqualTo("The sky is blue")
    }

    @Test
    fun `get returns the entry by id`() = runBlocking {
        val entry = repo.add("note", "hello world")
        val fetched = repo.get(entry.id)
        assertThat(fetched).isNotNull()
        assertThat(fetched?.content).isEqualTo("hello world")
    }

    @Test
    fun `get returns null for unknown id`() {
        assertThat(repo.get("nonexistent-id")).isNull()
    }

    @Test
    fun `byType filters by type`() = runBlocking {
        repo.add("fact", "fact 1")
        repo.add("preference", "pref 1")
        repo.add("fact", "fact 2")
        val facts = repo.byType("fact")
        assertThat(facts).hasSize(2)
        val prefs = repo.byType("preference")
        assertThat(prefs).hasSize(1)
    }

    @Test
    fun `search returns entries matching the query case-insensitively`() = runBlocking {
        repo.add("note", "Physics lecture notes")
        repo.add("note", "Math homework")
        repo.add("fact", "The speed of light is 299792458 m/s")
        val matches = repo.search("physics")
        assertThat(matches).hasSize(1)
        assertThat(matches.first().content).contains("Physics")
    }

    @Test
    fun `search matches tags too`() = runBlocking {
        repo.add("note", "some content", tags = listOf("physics", "important"))
        repo.add("note", "other content", tags = listOf("math"))
        val matches = repo.search("physics")
        assertThat(matches).hasSize(1)
    }

    @Test
    fun `search with empty query returns all entries capped at limit`() = runBlocking {
        repo.add("note", "entry 1")
        repo.add("note", "entry 2")
        repo.add("note", "entry 3")
        val all = repo.search("")
        assertThat(all).hasSize(3)
        val limited = repo.search("", limit = 2)
        assertThat(limited).hasSize(2)
    }

    @Test
    fun `delete removes the entry and returns true`() = runBlocking {
        val entry = repo.add("note", "to be deleted")
        assertThat(repo.delete(entry.id)).isTrue()
        assertThat(repo.count()).isEqualTo(0)
        assertThat(repo.get(entry.id)).isNull()
    }

    @Test
    fun `delete returns false for unknown id`() = runBlocking {
        assertThat(repo.delete("nonexistent-id")).isFalse()
    }

    @Test
    fun `clear empties the store`() = runBlocking {
        repo.add("note", "a")
        repo.add("note", "b")
        repo.add("note", "c")
        repo.clear()
        assertThat(repo.count()).isEqualTo(0)
        // Persistence check.
        val reloaded = MemoryRepository(context)
        assertThat(reloaded.count()).isEqualTo(0)
    }

    @Test
    fun `count returns the correct size`() = runBlocking {
        assertThat(repo.count()).isEqualTo(0)
        repo.add("note", "a")
        repo.add("note", "b")
        assertThat(repo.count()).isEqualTo(2)
    }

    @Test
    fun `entries StateFlow updates after add`() = runBlocking {
        assertThat(repo.entries.value).isEmpty()
        repo.add("note", "first")
        assertThat(repo.entries.value).hasSize(1)
        repo.add("note", "second")
        assertThat(repo.entries.value).hasSize(2)
    }
}
