package com.kaiser.aiagent.domain.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Centralized enforcement of [ToolPermissionLevel] for tool execution.
 *
 * The [AgentRuntime] calls [enforce] before invoking a tool's [execute]
 * method. The flow is:
 *
 *   SAFE                  -> returns immediately (ExecutionResult.Granted)
 *   CONFIRMATION_REQUIRED -> suspends until the UI resolves a pending
 *                            confirmation request. The UI observes
 *                            [pendingConfirmation] and shows a dialog.
 *                            The user's decision resolves the deferred.
 *   BLOCKED               -> returns ExecutionResult.Denied immediately.
 *
 * The manager is intentionally simple — a single pending request at a
 * time. Multiple concurrent confirmation requests would require a queue;
 * for v0.4 the agent runtime executes tools sequentially anyway, so this
 * is sufficient.
 */
class PermissionManager {

    /**
     * Live state of any pending confirmation request. The UI (ChatScreen)
     * observes this and renders a dialog when non-null.
     */
    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    /**
     * Per-tool overrides. The user can downgrade a tool's level via the
     * Debug screen (e.g. block a CONFIRMATION_REQUIRED tool). Empty by
     * default — tools use their declared [AgentTool.permissionLevel].
     *
     * v0.4 exposes this only via the in-memory API; persistence is a
     * future concern.
     */
    private val overrides: MutableMap<String, ToolPermissionLevel> = mutableMapOf()

    /**
     * Returns the *effective* permission level for a tool — its declared
     * level, or the override if one is set.
     */
    fun effectiveLevel(tool: AgentTool): ToolPermissionLevel =
        overrides[tool.name] ?: tool.permissionLevel

    /** Sets or clears an override for the named tool. */
    fun setOverride(toolName: String, level: ToolPermissionLevel?) {
        if (level == null) overrides.remove(toolName) else overrides[toolName] = level
    }

    /**
     * Enforces the permission level before tool execution. Suspends until
     * a decision is reached (immediately for SAFE / BLOCKED, awaits UI
     * for CONFIRMATION_REQUIRED).
     */
    suspend fun enforce(
        tool: AgentTool,
        arguments: String,
        conversationId: String?
    ): ExecutionResult {
        return when (effectiveLevel(tool)) {
            ToolPermissionLevel.SAFE -> ExecutionResult.Granted
            ToolPermissionLevel.BLOCKED -> {
                Timber.i("Tool %s blocked by policy", tool.name)
                ExecutionResult.Denied("Tool '${tool.name}' is blocked by permission policy.")
            }
            ToolPermissionLevel.CONFIRMATION_REQUIRED -> {
                val deferred = CompletableDeferred<Boolean>()
                val request = PendingConfirmation(
                    toolName = tool.name,
                    toolDescription = tool.description,
                    argumentsJson = arguments,
                    conversationId = conversationId,
                    result = deferred
                )
                _pendingConfirmation.value = request
                Timber.i("Tool %s awaiting user confirmation", tool.name)
                val approved = deferred.await()
                _pendingConfirmation.value = null
                if (approved) ExecutionResult.Granted
                else ExecutionResult.Denied("User denied permission for '${tool.name}'.")
            }
        }
    }

    /** Called by the UI when the user approves the pending confirmation. */
    fun approve() {
        _pendingConfirmation.value?.result?.complete(true)
    }

    /** Called by the UI when the user denies the pending confirmation. */
    fun deny() {
        _pendingConfirmation.value?.result?.complete(false)
    }

    /** Result of a permission check. */
    sealed class ExecutionResult {
        /** Tool may be executed. */
        data object Granted : ExecutionResult()
        /**
         * Tool may not be executed. [reason] is returned to the model as
         * the tool-result error message.
         */
        data class Denied(val reason: String) : ExecutionResult()
    }

    /**
     * A pending confirmation request. The UI renders this as a dialog
     * with Approve / Deny buttons. Calling [result].complete(true/false)
     * resolves the request.
     */
    data class PendingConfirmation(
        val toolName: String,
        val toolDescription: String,
        val argumentsJson: String,
        val conversationId: String?,
        val result: CompletableDeferred<Boolean>
    )
}
