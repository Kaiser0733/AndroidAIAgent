package com.kaiser.aiagent.data.updater

/**
 * Result of comparing the installed app version against the latest GitHub
 * release. The UI uses this to decide what dialog (if any) to present.
 */
sealed class UpdateCheckResult {

    /** App is up to date. */
    data object UpToDate : UpdateCheckResult()

    /**
     * A newer version is available.
     * @param latest Parsed release metadata from GitHub.
     * @param assetUrl Direct download URL of the APK asset chosen for this
     *   device, or null if no suitable asset was found.
     */
    data class Available(
        val latest: GitHubRelease,
        val assetUrl: String?
    ) : UpdateCheckResult()

    /** Check failed (network, parsing, etc.). */
    data class Failed(val message: String) : UpdateCheckResult()
}
