package com.kaiser.aiagent.tools.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import com.kaiser.aiagent.domain.tools.stringParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v0.6 Phase 2: Opens an app by name.
 * Uses PackageManager to find the app and launches it.
 *
 * Examples: "Open YouTube", "Open Settings", "Open Chrome"
 */
class OpenAppTool(private val context: Context) : AgentTool {
    override val name = "open_app"
    override val description = "Opens an app on the device by name. " +
        "Use when the user asks to 'open', 'launch', or 'start' an app. " +
        "Examples: YouTube, Settings, Chrome, WhatsApp, Gmail, Camera."
    override val argumentsSchema = """{"app_name":"<app name>"}"""
    override val permissionLevel = ToolPermissionLevel.SAFE

    private val json = Json { ignoreUnknownKeys = true }

    override fun parametersJsonSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("app_name", stringParam("Name of the app to open (e.g. YouTube, Settings, Chrome)"))
        })
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("app_name"))))
    }

    override suspend fun execute(arguments: String): ToolResult = withContext(Dispatchers.IO) {
        val obj = try { json.parseToJsonElement(arguments.ifBlank{"{}"}) as? JsonObject } catch(e:Exception){null}
        val appName = (obj?.get("app_name") as? JsonPrimitive)?.content
            ?: return@withContext ToolResult(false, "", "Missing 'app_name' argument.")

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolvedActivities = pm.queryIntentActivities(intent, 0)

        // Search for matching app by name (case-insensitive)
        val match = resolvedActivities.find { ri ->
            val label = ri.loadLabel(pm).toString().lowercase()
            val pkg = ri.activityInfo.packageName.lowercase()
            label.contains(appName.lowercase()) || pkg.contains(appName.lowercase())
        }

        if (match == null) {
            // List available apps as suggestions
            val available = resolvedActivities.take(15).joinToString(", ") { it.loadLabel(pm).toString() }
            return@withContext ToolResult(
                false, "",
                "App '$appName' not found. Available apps: $available"
            )
        }

        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launchIntent == null) {
            return@withContext ToolResult(false, "", "Could not create launch intent for ${match.activityInfo.packageName}")
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // v0.6.2: Enter Picture-in-Picture mode BEFORE launching the other app.
        // This keeps the AI Agent chat visible as a floating window.
        try {
            val activity = findMainActivity()
            if (activity != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val pipParams = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(9, 16))
                    .build()
                activity.enterPictureInPictureMode(pipParams)
                // Small delay to let PiP animation start
                Thread.sleep(300)
            }
        } catch (e: Exception) {
            // PiP not supported or failed — continue without it
        }

        context.startActivity(launchIntent)

        val appNameResolved: String = match.loadLabel(pm).toString()
        ToolResult(
            success = true,
            data = buildJsonObject {
                put("app_name", appNameResolved)
                put("package", match.activityInfo.packageName)
                put("opened", true)
            }.toString(),
            error = null,
            metadata = mapOf("app" to appNameResolved)
        )
    }

    /** Finds the current MainActivity for PiP entry. */
    private fun findMainActivity(): Activity? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val activities = activityThread?.javaClass
                ?.getDeclaredField("mActivityList")?.apply { isAccessible = true }
                ?.get(activityThread) as? Map<*, *>
            activities?.values?.firstOrNull { it is Activity } as? Activity
        } catch (e: Exception) {
            null
        }
    }
}
