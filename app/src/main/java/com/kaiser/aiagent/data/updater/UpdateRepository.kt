package com.kaiser.aiagent.data.updater

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaiser.aiagent.data.logging.LogRepository
import com.kaiser.aiagent.data.remote.RemoteConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

private val Context.updaterStore by preferencesDataStore("updater_prefs")

/**
 * Coordinates the update workflow:
 *   1. Query the GitHub Releases API for the latest release in `repo`.
 *   2. Compare its `tag_name` against the installed version.
 *   3. Download the matching APK asset to a cache directory.
 *   4. Hand off to the Android package installer via the [UpdateStarter].
 *
 * The repository URL is configurable from Settings; it defaults to the
 * project's own GitHub repository.
 *
 * v0.1 only supports the "latest release" endpoint and a single APK asset
 * per release. A future version may paginate releases, support delta
 * updates, or verify APK signatures before installation.
 */
class UpdateRepository(
    private val context: Context,
    private val logRepository: LogRepository
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val repoKey = stringPreferencesKey("updater_repo")

    /** Flow of the currently configured GitHub repository (`owner/name`). */
    val repoFlow: Flow<String> = context.updaterStore.data.map { prefs ->
        prefs[repoKey] ?: DEFAULT_REPO
    }

    suspend fun setRepo(repo: String) {
        context.updaterStore.edit { it[repoKey] = repo }
    }

    /**
     * Checks GitHub for the latest release and compares it with the
     * currently installed version. Always returns a [UpdateCheckResult] —
     * never throws.
     */
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val repo = repoFlow.first()
            val url = "https://api.github.com/repos/$repo/releases/latest"
            logRepository.appendUpdateLog("Checking $url")
            Timber.i("Checking for updates at %s", url)

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AndroidAIAgent-Updater")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "GitHub API returned HTTP ${response.code}"
                    logRepository.appendUpdateLog("Check failed: $msg")
                    return@withContext UpdateCheckResult.Failed(msg)
                }
                val body = response.body?.string()
                    ?: return@withContext UpdateCheckResult.Failed("Empty response body")
                val release = json.decodeFromString<GitHubRelease>(body)
                val installedVersion = getCurrentVersionName()

                val latestVersion = release.tagName.removePrefix("v")
                logRepository.appendUpdateLog(
                    "Installed=$installedVersion, latest=$latestVersion"
                )

                val cmp = compareVersions(latestVersion, installedVersion)
                if (cmp <= 0) {
                    UpdateCheckResult.UpToDate
                } else {
                    val asset = pickApkAsset(release)
                    UpdateCheckResult.Available(
                        latest = release,
                        assetUrl = asset?.downloadUrl
                    )
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "Update check failed")
            logRepository.appendUpdateLog("Check failed: ${t.message}")
            UpdateCheckResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Downloads the APK from [assetUrl] into the app's external cache dir
     * under `updates/`, then invokes the system package installer.
     * Returns the downloaded file on success; throws on failure.
     */
    suspend fun downloadAndInstall(assetUrl: String): File = withContext(Dispatchers.IO) {
        val targetDir = File(context.externalCacheDir, "updates").apply { mkdirs() }
        val fileName = assetUrl.substringAfterLast("/").substringBefore("?")
        val target = File(targetDir, fileName.ifBlank { "update.apk" })

        logRepository.appendUpdateLog("Downloading $assetUrl -> ${target.absolutePath}")
        Timber.i("Downloading update to %s", target.absolutePath)

        val request = Request.Builder().url(assetUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateException("HTTP ${response.code} downloading APK")
            }
            response.body?.byteStream()?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw UpdateException("Empty response body downloading APK")
        }

        logRepository.appendUpdateLog("Downloaded ${target.length()} bytes")
        UpdateStarter.install(context, target)
        target
    }

    // ---- Helpers --------------------------------------------------------

    private fun getCurrentVersionName(): String {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        return info.versionName.orEmpty()
    }

    /**
     * Picks the APK asset that best matches the current device ABI. We
     * prefer an architecture-specific build, falling back to a universal
     * APK if present. If multiple APKs are available and none matches,
     * the first APK asset is returned as a best-effort choice.
     */
    private fun pickApkAsset(release: GitHubRelease): GitHubAsset? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null

        val abis = Build.SUPPORTED_ABIS
        for (abi in abis) {
            val match = apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }
            if (match != null) return match
        }
        val universal = apks.firstOrNull {
            it.name.contains("universal", ignoreCase = true)
        }
        return universal ?: apks.first()
    }

    /**
     * Compares two dotted version strings (`MAJOR.MINOR.PATCH…`).
     * Returns a negative int if `a < b`, 0 if equal, positive if `a > b`.
     * Non-numeric segments are compared lexicographically.
     */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").toMutableList()
        val pb = b.split(".").toMutableList()
        val n = maxOf(pa.size, pb.size)
        while (pa.size < n) pa.add("0")
        while (pb.size < n) pb.add("0")
        for (i in 0 until n) {
            val ia = pa[i].toIntOrNull()
            val ib = pb[i].toIntOrNull()
            if (ia != null && ib != null) {
                if (ia != ib) return ia - ib
            } else {
                val c = pa[i].compareTo(pb[i])
                if (c != 0) return c
            }
        }
        return 0
    }

    companion object {
        const val DEFAULT_REPO = "Kaiser0733/Update-mobile-agent"
    }
}

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)
