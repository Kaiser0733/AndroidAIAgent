package com.kaiser.aiagent.data.storage

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
 * Unit tests for [StorageRepository]'s search_files and file_info
 * operations. Uses Robolectric to provide a real Android Context with
 * a writable filesDir.
 *
 * Covers:
 *  - listFiles on a populated dir
 *  - listFiles on a non-existent path (empty result, not exception)
 *  - searchFiles by name query
 *  - searchFiles by extension
 *  - searchFiles by name + extension combo
 *  - searchFiles caps at maxMatches
 *  - fileInfo returns null for non-existent path
 *  - fileInfo returns metadata for existing file
 *  - readTextFile returns null for non-existent path
 *  - readTextFile returns content for existing file
 *  - readTextFile truncates at maxBytes
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StorageRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: StorageRepository
    private lateinit var testDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = StorageRepository(context)
        testDir = File(context.filesDir, "test_storage").apply { mkdirs() }
        // Populate test files.
        File(testDir, "physics_notes.txt").writeText("physics content")
        File(testDir, "math_notes.md").writeText("# Math\nsome content")
        File(testDir, "report.pdf").writeText("fake pdf content")
        File(testDir, "image.jpg").writeText("fake jpg")
        File(testDir, "data.json").writeText("{\"a\":1}")
        // Subdirectory
        val subDir = File(testDir, "subfolder").apply { mkdirs() }
        File(subDir, "nested.txt").writeText("nested content")
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `listFiles returns files and folders in a populated dir`() = runBlocking {
        val result = repo.listFiles(testDir.absolutePath)
        assertThat(result.folders).hasSize(1)
        assertThat(result.folders.first().name).isEqualTo("subfolder")
        assertThat(result.files.map { it.name }).containsAtLeast(
            "physics_notes.txt", "math_notes.md", "report.pdf", "image.jpg", "data.json"
        )
        assertThat(result.truncated).isFalse()
        Unit
    }

    @Test
    fun `listFiles on non-existent path returns empty result`() = runBlocking {
        val result = repo.listFiles("/nonexistent/path/that/does/not/exist")
        assertThat(result.files).isEmpty()
        assertThat(result.folders).isEmpty()
        assertThat(result.totalInDir).isEqualTo(0)
        Unit
    }

    @Test
    fun `searchFiles by name query returns matching files`() = runBlocking {
        val result = repo.searchFiles(
            query = "physics",
            roots = listOf(testDir.absolutePath)
        )
        assertThat(result.matches).hasSize(1)
        assertThat(result.matches.first().name).isEqualTo("physics_notes.txt")
        Unit
    }

    @Test
    fun `searchFiles by extension returns matching files`() = runBlocking {
        val result = repo.searchFiles(
            query = "",
            extensions = listOf("txt"),
            roots = listOf(testDir.absolutePath)
        )
        val names = result.matches.map { it.name }.sorted()
        assertThat(names).containsAtLeast("nested.txt", "physics_notes.txt")
        Unit
    }

    @Test
    fun `searchFiles by name and extension combo`() = runBlocking {
        val result = repo.searchFiles(
            query = "notes",
            extensions = listOf("txt"),
            roots = listOf(testDir.absolutePath)
        )
        assertThat(result.matches).hasSize(1)
        assertThat(result.matches.first().name).isEqualTo("physics_notes.txt")
        Unit
    }

    @Test
    fun `searchFiles caps at maxMatches`() = runBlocking {
        // Create many files.
        repeat(50) { File(testDir, "filler_$it.txt").writeText("x") }
        val result = repo.searchFiles(
            query = "filler",
            roots = listOf(testDir.absolutePath),
            maxMatches = 10
        )
        assertThat(result.matches.size).isAtMost(10)
        assertThat(result.truncated).isTrue()
        Unit
    }

    @Test
    fun `fileInfo returns null for non-existent path`() = runBlocking {
        val result = repo.fileInfo("/nonexistent/file.txt")
        assertThat(result).isNull()
        Unit
    }

    @Test
    fun `fileInfo returns metadata for existing file`() = runBlocking {
        val target = File(testDir, "report.pdf")
        val result = repo.fileInfo(target.absolutePath)
        assertThat(result).isNotNull()
        assertThat(result?.name).isEqualTo("report.pdf")
        assertThat(result?.isDirectory).isFalse()
        assertThat(result?.sizeBytes).isGreaterThan(0L)
        assertThat(result?.mimeType).isEqualTo("application/pdf")
        Unit
    }

    @Test
    fun `readTextFile returns null for non-existent path`() = runBlocking {
        val result = repo.readTextFile("/nonexistent/file.txt")
        assertThat(result).isNull()
        Unit
    }

    @Test
    fun `readTextFile returns content for existing file`() = runBlocking {
        val target = File(testDir, "physics_notes.txt")
        val result = repo.readTextFile(target.absolutePath)
        assertThat(result).isEqualTo("physics content")
        Unit
    }

    @Test
    fun `readTextFile truncates at maxBytes`() = runBlocking {
        val target = File(testDir, "big.txt")
        target.writeText("x".repeat(1000))
        val result = repo.readTextFile(target.absolutePath, maxBytes = 100)
        assertThat(result?.length).isEqualTo(100)
        Unit
    }

    @Test
    fun `createFolder creates a new folder`() = runBlocking {
        val path = repo.createFolder(testDir.absolutePath, "NewFolder")
        assertThat(path).isNotNull()
        assertThat(File(path!!).exists()).isTrue()
        assertThat(File(path).isDirectory).isTrue()
        Unit
    }

    @Test
    fun `createFolder refuses path traversal`() = runBlocking {
        val path = repo.createFolder(testDir.absolutePath, "../escape")
        assertThat(path).isNull()
        Unit
    }

    @Test
    fun `createTextFile creates a new file with content`() = runBlocking {
        val path = repo.createTextFile(testDir.absolutePath, "new.txt", "hello")
        assertThat(path).isNotNull()
        assertThat(File(path!!).readText()).isEqualTo("hello")
        Unit
    }

    @Test
    fun `createTextFile refuses to overwrite existing files`() = runBlocking {
        val existing = File(testDir, "existing.txt")
        existing.writeText("original")
        val path = repo.createTextFile(testDir.absolutePath, "existing.txt", "new content")
        assertThat(path).isNull()
        assertThat(existing.readText()).isEqualTo("original")
        Unit
    }
}
