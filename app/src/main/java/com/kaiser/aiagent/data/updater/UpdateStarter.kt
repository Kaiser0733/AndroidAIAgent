package com.kaiser.aiagent.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File

/**
 * Bridges between the updater and the Android package installer.
 *
 * Handles the API level differences for granting read permission to the
 * installer (`FLAG_GRANT_READ_URI_PERMISSION` on modern Android). The
 * actual install confirmation dialog is shown by the system — we never
 * install silently.
 *
 * The caller is responsible for ensuring the file is a valid APK and that
 * the user has opted into installing it.
 */
object UpdateStarter {

    fun install(context: Context, apk: File) {
        Timber.i("Triggering system installer for %s", apk.absolutePath)
        if (!apk.exists()) {
            throw UpdateException("APK file not found: ${apk.absolutePath}")
        }

        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apk)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Required for content:// URIs on N+
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        context.startActivity(intent)
    }
}
