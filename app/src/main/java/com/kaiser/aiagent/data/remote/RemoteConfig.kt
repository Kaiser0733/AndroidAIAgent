package com.kaiser.aiagent.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schema for the remote JSON configuration file.
 *
 * The remote file lives at a configurable URL (see [SettingsViewModel]) and
 * is fetched at app start and on user demand. It is the primary mechanism
 * for remotely disabling or throttling the agent without shipping an app
 * update.
 *
 * All fields are optional so a partial / older config remains compatible.
 *
 * Example JSON:
 * ```json
 * {
 *   "latestVersion": "0.2",
 *   "minimumVersion": "0.1",
 *   "disableAgent": false,
 *   "maintenanceMode": false,
 *   "maintenanceMessage": "Back soon."
 * }
 * ```
 */
@Serializable
data class RemoteConfig(
    /** Latest published version known to the server (informational). */
    @SerialName("latestVersion") val latestVersion: String? = null,
    /** Lowest version that is still allowed to run. Below this, force update. */
    @SerialName("minimumVersion") val minimumVersion: String? = null,
    /** If true, the agent is hard-disabled and shows a blocking screen. */
    @SerialName("disableAgent") val disableAgent: Boolean = false,
    /** If true, the agent shows a maintenance screen instead of running. */
    @SerialName("maintenanceMode") val maintenanceMode: Boolean = false,
    /** Optional human-readable message shown on the maintenance screen. */
    @SerialName("maintenanceMessage") val maintenanceMessage: String? = null
)
