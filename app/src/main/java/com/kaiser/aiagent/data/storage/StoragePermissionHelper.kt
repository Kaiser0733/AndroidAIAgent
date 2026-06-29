package com.kaiser.aiagent.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Helper for checking and requesting storage permissions.
 *
 * v0.4.1 introduces this helper because v0.4's file tools failed
 * silently on Android 10+ due to scoped storage restrictions. The
 * agent could only see files it created itself — not the user's
 * existing PDFs, DOCXs, etc. in Downloads/Documents.
 *
 * The fix: request MANAGE_EXTERNAL_STORAGE (Android 11+). This is a
 * "special permission" that cannot be granted via the normal permission
 * dialog — the user must toggle it in system settings. This helper
 * provides:
 *   - [isFullStorageAccessGranted] — check current state
 *   - [openFullStorageAccessSettings] — open the system page so the
 *     user can grant it
 *
 * On Android 10 and below, the legacy READ/WRITE_EXTERNAL_STORAGE
 * permissions work via the normal dialog, so this helper reports
 * `true` if those are granted.
 */
class StoragePermissionHelper(private val context: Context) {

    /**
     * Returns true if the app has enough storage access for the file
     * tools to find the user's existing files in shared storage.
     *
     * On Android 11+: true if MANAGE_EXTERNAL_STORAGE is granted.
     * On Android 10 and below: true if READ_EXTERNAL_STORAGE is granted
     * (or if we're on an emulator with no scoped storage enforcement).
     */
    fun isFullStorageAccessGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Android 10 and below — check legacy READ_EXTERNAL_STORAGE.
            val granted = context.checkSelfPermission(READ_EXT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            granted
        }
    }

    /**
     * Opens the system settings page where the user can grant
     * "All files access" (MANAGE_EXTERNAL_STORAGE) to this app.
     *
     * No-op on Android 10 and below (legacy permissions work via the
     * normal dialog instead).
     */
    fun openFullStorageAccessSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // Some OEMs don't implement ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION.
            // Fall back to the generic "All files access" page.
            try {
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    companion object {
        private val READ_EXT = android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
