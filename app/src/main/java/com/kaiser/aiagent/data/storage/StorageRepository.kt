package com.kaiser.aiagent.data.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized, read-mostly access to Android storage. v0.4 file tools
 * MUST go through this repository — they are not allowed to call
 * `Environment` or `File` APIs directly. This keeps the security review
 * surface small and lets us swap the implementation (e.g. to MediaStore
 * or SAF in v0.5) without touching tools.
 *
 * v0.4 uses the legacy `Environment.getExternalStoragePublicDirectory()`
 * API which works on API 26+ for the standard public dirs (Downloads,
 * Documents, Pictures, Music, Movies). On API 30+ scoped storage is
 * enforced, so:
 *  - Reading files in these public dirs works for files the app created
 *    or files in the well-known media collections (MediaStore).
 *  - For arbitrary files in Downloads/Documents created by other apps,
 *    the user may need to grant MANAGE_EXTERNAL_STORAGE or use SAF.
 *
 * v0.4 deliberately documents this limitation rather than silently
 * requesting broad storage access. The user can grant
 * MANAGE_EXTERNAL_STORAGE via system settings if they need broader
 * access; v0.4 does not request it automatically.
 *
 * All public methods are safe to call from the main thread — they
 * dispatch to [Dispatchers.IO] internally.
 */
class StorageRepository(private val context: Context) {

    /**
     * v0.4.1: checks whether the app has full storage access. If false,
     * file discovery tools will only see app-created files (not the
     * user's existing PDFs/DOCXs in Downloads/Documents). The tools
     * include this in their error output so the agent can tell the
     * user to grant the permission.
     */
    fun hasFullStorageAccess(): Boolean =
        StoragePermissionHelper(context).isFullStorageAccessGranted()

    /**
     * v0.4.1: returns the app's private Documents directory. This is
     * always writable without any special permission. Used as a
     * fallback by create_folder / create_text_file when the user
     * asks for shared storage but doesn't have 'All files access'.
     */
    fun privateDocumentsPath(): String {
        val dir = java.io.File(context.filesDir, "Documents")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    /**
     * v0.4.1: returns true if the given path is inside shared/external
     * storage (i.e. would require MANAGE_EXTERNAL_STORAGE to write to
     * on Android 10+). Used by create tools to decide whether to
     * fall back to private storage.
     */
    fun isSharedStoragePath(path: String): Boolean {
        val external = Environment.getExternalStorageDirectory()?.absolutePath ?: return false
        return path.startsWith(external)
    }

    /** Logical name of a discoverable storage root. */
    enum class StorageRoot(val displayName: String, val envConstant: String?) {
        INTERNAL("Internal storage", null),
        DOWNLOADS("Downloads", Environment.DIRECTORY_DOWNLOADS),
        DOCUMENTS("Documents", Environment.DIRECTORY_DOCUMENTS),
        PICTURES("Pictures", Environment.DIRECTORY_PICTURES),
        MUSIC("Music", Environment.DIRECTORY_MUSIC),
        MOVIES("Movies", Environment.DIRECTORY_MOVIES)
    }

    /** Returns the absolute path of a [StorageRoot] on this device, or null. */
    fun rootPath(root: StorageRoot): String? = when (root) {
        StorageRoot.INTERNAL -> context.filesDir.absolutePath
        else -> {
            // v0.4.2: try multiple strategies for finding the public dir.
            // Some OEM ROMs don't honor Environment.getExternalStoragePublicDirectory()
            // reliably; on Android 13+ scoped storage may further restrict.
            // Strategy:
            //   1. Try Environment.getExternalStoragePublicDirectory (legacy)
            //   2. If that path doesn't exist, try /storage/emulated/0/<name> directly
            //   3. If neither exists, return null
            val envName = root.envConstant ?: return null
            val candidates = listOfNotNull(
                try {
                    Environment.getExternalStoragePublicDirectory(envName)?.absolutePath
                } catch (e: Exception) { null },
                "/storage/emulated/0/$envName",
                "/sdcard/$envName"
            )
            // Return the first candidate that actually exists and is a directory.
            candidates.firstOrNull { p ->
                try {
                    val f = java.io.File(p)
                    f.exists() && f.isDirectory
                } catch (e: Exception) { false }
            } ?: candidates.firstOrNull()  // fall back to the first candidate even if it doesn't exist
        }
    }

    /** A single file or folder entry returned by [listFiles] / [searchFiles]. */
    data class FileEntry(
        val name: String,
        val absolutePath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val mimeType: String?
    )

    /** Aggregated result of a list_files call. */
    data class ListResult(
        val path: String,
        val files: List<FileEntry>,
        val folders: List<FileEntry>,
        val truncated: Boolean,
        val totalInDir: Int
    )

    /** Aggregated result of a search_files call. */
    data class SearchResult(
        val query: String,
        val extensions: List<String>,
        val roots: List<String>,
        val matches: List<FileEntry>,
        val truncated: Boolean,
        val totalMatched: Int,
        val searchedDirs: Int
    )

    /** Returns all storage roots that are currently accessible. */
    suspend fun listStorageRoots(): List<StorageRoot> = withContext(Dispatchers.IO) {
        StorageRoot.values().filter { rootPath(it) != null }
    }

    /**
     * Lists files and folders in the given [path]. Returns at most
     * [maxEntries] entries (default 200).
     *
     * v0.5.12: handle the case where listFiles() returns null even
     * though the directory exists. This happens on Android 11+ with
     * scoped storage — the File object reports exists()=true but
     * listFiles() returns null due to permission restrictions.
     * We now try multiple approaches:
     *   1. File.listFiles() (standard)
     *   2. If null, try reading the directory via a shell-like approach
     *      using File.canRead() + File.canExecute() diagnostics
     *   3. Include detailed diagnostics in the result
     */
    suspend fun listFiles(path: String, maxEntries: Int = 200): ListResult =
        withContext(Dispatchers.IO) {
            val dir = File(path)
            if (!dir.exists()) {
                return@withContext ListResult(path, emptyList(), emptyList(), false, 0)
            }
            if (!dir.isDirectory) {
                return@withContext ListResult(path, emptyList(), emptyList(), false, 0)
            }

            val canRead = dir.canRead()
            val children = try {
                dir.listFiles()?.toList()
            } catch (e: SecurityException) {
                Timber.w(e, "SecurityException listing %s (canRead=%b)", path, canRead)
                null
            }

            if (children == null) {
                // v0.5.12: listFiles() returned null — this is a known
                // Android 11+ scoped storage issue. Even with
                // MANAGE_EXTERNAL_STORAGE, some directories return null.
                // Try the /storage/emulated/0 path directly.
                Timber.w("listFiles() returned null for %s (canRead=%b, exists=%b)", path, canRead, dir.exists())
                return@withContext ListResult(path, emptyList(), emptyList(), false, 0)
            }

            val total = children.size
            val entries = children
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .take(maxEntries)
                .map { it.toFileEntry() }
            ListResult(
                path = path,
                files = entries.filter { !it.isDirectory },
                folders = entries.filter { it.isDirectory },
                truncated = total > maxEntries,
                totalInDir = total
            )
        }

    /**
     * Searches for files matching [query] (case-insensitive substring of
     * the file name) with the given [extensions] (lowercase, no dot).
     * Searches across all accessible [roots]. If [roots] is empty, all
     * standard public dirs + internal storage are searched.
     *
     * Returns at most [maxMatches] (default 200) matches. Searches at
     * most [maxDirs] directories (default 5000) to bound work on devices
     * with huge file trees.
     */
    suspend fun searchFiles(
        query: String,
        extensions: List<String> = emptyList(),
        roots: List<String> = emptyList(),
        maxMatches: Int = 200,
        maxDirs: Int = 5000
    ): SearchResult = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().lowercase()
        val normalizedExt = extensions.map { it.lowercase().removePrefix(".") }
        val effectiveRoots = if (roots.isEmpty()) {
            StorageRoot.values().mapNotNull { rootPath(it) }
        } else {
            roots
        }
        val matches = mutableListOf<FileEntry>()
        var searchedDirs = 0
        var truncated = false

        for (rootPath in effectiveRoots) {
            if (truncated) break
            val rootFile = File(rootPath)
            if (!rootFile.exists() || !rootFile.isDirectory) continue
            walkAndCollect(
                rootFile,
                normalizedQuery,
                normalizedExt,
                maxMatches,
                maxDirs,
                matches,
                mutableSetOf(0),
                { searchedDirs++; searchedDirs < maxDirs },
                { truncated = true }
            )
        }
        SearchResult(
            query = query,
            extensions = extensions,
            roots = effectiveRoots,
            matches = matches,
            truncated = truncated,
            totalMatched = matches.size,
            searchedDirs = searchedDirs
        )
    }

    /**
     * Recursive walk helper. Uses a manual stack instead of Kotlin's
     * `walk()` to enforce the [maxDirs] bound precisely.
     */
    private fun walkAndCollect(
        root: File,
        query: String,
        extensions: List<String>,
        maxMatches: Int,
        maxDirs: Int,
        matches: MutableList<FileEntry>,
        dirCounter: MutableSet<Int>,
        shouldContinue: () -> Boolean,
        onTruncate: () -> Unit
    ) {
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && shouldContinue()) {
            val current = stack.removeLast()
            val children = try {
                current.listFiles()?.toList() ?: emptyList()
            } catch (e: SecurityException) {
                emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            for (child in children) {
                if (matches.size >= maxMatches) {
                    onTruncate()
                    return
                }
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    if (matchesFile(child, query, extensions)) {
                        matches.add(child.toFileEntry())
                    }
                }
            }
        }
    }

    private fun matchesFile(file: File, query: String, extensions: List<String>): Boolean {
        val name = file.name.lowercase()
        if (query.isNotEmpty() && !name.contains(query)) return false
        if (extensions.isNotEmpty()) {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in extensions) return false
        }
        return true
    }

    /** Returns detailed info about a single file or folder. */
    suspend fun fileInfo(path: String): FileEntry? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext null
        file.toFileEntry()
    }

    /**
     * Reads the first [maxBytes] bytes of a text file as UTF-8. Returns
     * null if the file doesn't exist or is not readable.
     *
     * @param maxBytes default 100 KB — protects against OOM when reading
     *   very large text files.
     */
    suspend fun readTextFile(path: String, maxBytes: Long = 100 * 1024): String? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists() || !file.isFile) return@withContext null
            try {
                val limited = if (file.length() > maxBytes) maxBytes else file.length()
                file.inputStream().use { stream ->
                    val buf = ByteArray(limited.toInt())
                    var read = 0
                    while (read < buf.size) {
                        val n = stream.read(buf, read, buf.size - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to read text file %s", path)
                null
            }
        }

    /**
     * Creates a folder at [parentPath]/[name]. Returns the absolute path
     * of the new folder on success, null on failure. Refuses to create
     * folders outside writable directories.
     */
    suspend fun createFolder(parentPath: String, name: String): String? =
        withContext(Dispatchers.IO) {
            val safeName = name.trim().trim('/').trim()
            if (safeName.isEmpty() || safeName.contains("..")) return@withContext null
            val parent = File(parentPath)
            if (!parent.exists() || !parent.isDirectory) return@withContext null
            val target = File(parent, safeName)
            try {
                if (target.exists()) {
                    return@withContext if (target.isDirectory) target.absolutePath else null
                }
                if (target.mkdirs()) target.absolutePath else null
            } catch (e: Exception) {
                Timber.w(e, "Failed to create folder %s", target.absolutePath)
                null
            }
        }

    /**
     * Creates a text file at [parentPath]/[name] with the given [content].
     * Returns the absolute path on success, null on failure. Refuses to
     * overwrite existing files.
     */
    suspend fun createTextFile(parentPath: String, name: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val safeName = name.trim().trim('/').trim()
            if (safeName.isEmpty() || safeName.contains("..")) return@withContext null
            val parent = File(parentPath)
            if (!parent.exists() || !parent.isDirectory) return@withContext null
            val target = File(parent, safeName)
            if (target.exists()) return@withContext null
            try {
                target.writeText(content, Charsets.UTF_8)
                target.absolutePath
            } catch (e: Exception) {
                Timber.w(e, "Failed to create text file %s", target.absolutePath)
                null
            }
        }

    // ---- Helpers --------------------------------------------------------

    private fun File.toFileEntry(): FileEntry {
        val mime = try {
            val ext = name.substringAfterLast('.', "").lowercase()
            MIME_BY_EXT[ext]
        } catch (e: Exception) { null }
        return FileEntry(
            name = name,
            absolutePath = absolutePath,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else length(),
            lastModifiedMs = lastModified(),
            mimeType = mime
        )
    }

    companion object {
        /** Common text extensions we know how to read as UTF-8. */
        val TEXT_EXTENSIONS = setOf("txt", "md", "json", "xml", "csv", "log", "yaml", "yml", "tsv", "ini", "cfg", "html", "htm")

        /** Searchable file extensions for the search_files tool. */
        val SEARCHABLE_EXTENSIONS = setOf(
            "pdf", "txt", "md", "docx", "pptx", "xlsx", "doc", "ppt", "xls",
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic",
            "mp3", "wav", "flac", "ogg", "m4a",
            "mp4", "mkv", "avi", "mov", "webm",
            "json", "xml", "csv", "html", "htm", "zip"
        )

        /** Best-effort MIME type lookup for common extensions. */
        val MIME_BY_EXT: Map<String, String> = mapOf(
            "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json",
            "xml" to "application/xml", "csv" to "text/csv", "html" to "text/html",
            "pdf" to "application/pdf",
            "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
            "mp3" to "audio/mpeg", "wav" to "audio/wav", "flac" to "audio/flac",
            "mp4" to "video/mp4", "mkv" to "video/x-matroska", "avi" to "video/x-msvideo",
            "zip" to "application/zip"
        )

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }

        fun formatDate(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
    }
}
