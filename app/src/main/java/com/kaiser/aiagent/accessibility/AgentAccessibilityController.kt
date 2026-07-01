package com.kaiser.aiagent.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * Bridges the tool layer to the live [AgentAccessibilityService].
 *
 * Tools call [run] with a block that takes the service instance and
 * returns a result string. If the service is not running (user has
 * not granted accessibility permission yet), the controller returns
 * a clear error string that the model can surface to the user.
 *
 * All operations are dispatched on the caller's thread. The service's
 * methods are non-blocking (they call performAction / dispatchGesture
 * which enqueue work on the system), so it is safe to call them from
 * a coroutine on Dispatchers.IO.
 */
object AgentAccessibilityController {

    /** True if the accessibility service is enabled in system settings. */
    fun isServiceEnabled(context: Context): Boolean {
        val target = context.packageName + "/" +
            AgentAccessibilityService::class.java.name
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        while (splitter.hasNext()) {
            if (splitter.next().equals(target, ignoreCase = true)) return true
        }
        return false
    }

    /** Opens the system Accessibility settings page. */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Runs [block] on the live service instance. Returns the block's
     * result, or an error string if the service is not running.
     */
    fun run(action: String, block: (AgentAccessibilityService) -> String): String {
        val svc = AgentAccessibilityService.instance
            ?: return "ERROR: Accessibility service is not running. " +
                "Ask the user to open Settings → Accessibility → AI Agent and toggle it ON."
        return try {
            block(svc)
        } catch (t: Throwable) {
            "ERROR: $action failed — ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * v0.7: Runs [block] on the live service instance with a generic
     * return type (not just String). Used by YouTube scripts that need
     * to return structured data (lists of results, etc.).
     *
     * Returns null if the service is not running or the block throws.
     */
    fun <T> runTyped(action: String, block: (AgentAccessibilityService) -> T): T? {
        val svc = AgentAccessibilityService.instance ?: return null
        return try {
            block(svc)
        } catch (t: Throwable) {
            null
        }
    }
}
