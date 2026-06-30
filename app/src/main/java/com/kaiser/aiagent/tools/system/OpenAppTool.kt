package com.kaiser.aiagent.tools.system

import android.content.Context
import android.content.Intent
import android.os.Build
import com.kaiser.aiagent.domain.tools.AgentTool
import com.kaiser.aiagent.domain.tools.ToolPermissionLevel
import com.kaiser.aiagent.domain.tools.ToolResult
import com.kaiser.aiagent.domain.tools.stringParam
import com.kaiser.aiagent.floating.FloatingChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v0.6.2: Opens an app by name.
 *
 * Side effect: if the floating overlay permission is granted, this
 * tool also starts [FloatingChatService] so the chat stays visible
 * as a floating window on top of the app being opened. The user
 * can see agent responses without switching back.
 *
 * If the overlay permission is NOT granted, the tool still opens the
 * target app — it just doesn't show a floating window. The user will
 * see the system prompt ask them to grant the permission via Settings.
 */
class OpenAppTool(private val context: Context) : AgentTool {
    override val name = "open_app"
    override val description = "Opens an app on the device by name. " +
        "Use when the user asks to 'open', 'launch', or 'start' an app. " +
        "Examples: YouTube, Settings, Chrome, WhatsApp, Gmail, Camera. " +
        "After opening, call read_screen to see what is on screen, then " +
        "tap_text / type_text to interact with the app."
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

        val match = resolvedActivities.find { ri ->
            val label = ri.loadLabel(pm).toString().lowercase()
            val pkg = ri.activityInfo.packageName.lowercase()
            label.contains(appName.lowercase()) || pkg.contains(appName.lowercase())
        }

        if (match == null) {
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

        // v0.6.2: Start the floating chat overlay BEFORE launching the
        // other app. This keeps the AI Agent visible on top so the user
        // can continue chatting while the agent drives the target app.
        FloatingChatService.startIfPermitted(context)

        // Small delay to let the overlay window attach before we
        // switch to the other app — otherwise the overlay can briefly
        // flash on top of OUR activity and then get pushed behind.
        Thread.sleep(200)

        context.startActivity(launchIntent)

        // Give the other app a moment to come up before any subsequent
        // accessibility tool (read_screen / tap_text) tries to inspect
        // its window. 1s is usually enough for the launcher activity
        // to reach the foreground and start drawing.
        Thread.sleep(1000)

        val appNameResolved: String = match.loadLabel(pm).toString()
        ToolResult(
            success = true,
            data = buildJsonObject {
                put("app_name", appNameResolved)
                put("package", match.activityInfo.packageName)
                put("opened", true)
                put("hint", "Call read_screen next to see what is on screen, then tap_text or type_text to interact.")
            }.toString(),
            error = null,
            metadata = mapOf("app" to appNameResolved)
        )
    }
}
