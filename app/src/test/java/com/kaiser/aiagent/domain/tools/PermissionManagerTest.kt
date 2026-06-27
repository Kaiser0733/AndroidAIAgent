package com.kaiser.aiagent.domain.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Unit tests for [PermissionManager]. Covers all three permission
 * levels and the override mechanism.
 */
class PermissionManagerTest {

    @Test
    fun `SAFE tool returns Granted immediately`() = runBlocking {
        val pm = PermissionManager()
        val tool = SimpleTool("safe", ToolPermissionLevel.SAFE)
        val result = pm.enforce(tool, "{}", null)
        assertThat(result).isInstanceOf(PermissionManager.ExecutionResult.Granted::class.java)
    }

    @Test
    fun `BLOCKED tool returns Denied immediately`() = runBlocking {
        val pm = PermissionManager()
        val tool = SimpleTool("blocked", ToolPermissionLevel.BLOCKED)
        val result = pm.enforce(tool, "{}", null)
        assertThat(result).isInstanceOf(PermissionManager.ExecutionResult.Denied::class.java)
        assertThat((result as PermissionManager.ExecutionResult.Denied).reason).contains("blocked")
    }

    @Test
    fun `CONFIRMATION_REQUIRED suspends until approved`() = runBlocking {
        val pm = PermissionManager()
        val tool = SimpleTool("confirm", ToolPermissionLevel.CONFIRMATION_REQUIRED)
        // Launch enforce in a separate coroutine; it should suspend.
        val deferred = CompletableDeferred<PermissionManager.ExecutionResult>()
        GlobalScope.launch {
            deferred.complete(pm.enforce(tool, "{}", null))
        }
        // Give the coroutine time to suspend on the confirmation.
        delay(100)
        assertThat(pm.pendingConfirmation.value).isNotNull()
        // Approve and wait for the result.
        pm.approve()
        val result = deferred.await()
        assertThat(result).isInstanceOf(PermissionManager.ExecutionResult.Granted::class.java)
        assertThat(pm.pendingConfirmation.value).isNull()
    }

    @Test
    fun `CONFIRMATION_REQUIRED returns Denied when user denies`() = runBlocking {
        val pm = PermissionManager()
        val tool = SimpleTool("confirm", ToolPermissionLevel.CONFIRMATION_REQUIRED)
        val deferred = CompletableDeferred<PermissionManager.ExecutionResult>()
        GlobalScope.launch {
            deferred.complete(pm.enforce(tool, "{}", null))
        }
        delay(100)
        pm.deny()
        val result = deferred.await()
        assertThat(result).isInstanceOf(PermissionManager.ExecutionResult.Denied::class.java)
        assertThat((result as PermissionManager.ExecutionResult.Denied).reason).contains("denied")
    }

    @Test
    fun `Override downgrades SAFE tool to BLOCKED`() = runBlocking {
        val pm = PermissionManager()
        val tool = SimpleTool("safe", ToolPermissionLevel.SAFE)
        pm.setOverride("safe", ToolPermissionLevel.BLOCKED)
        assertThat(pm.effectiveLevel(tool)).isEqualTo(ToolPermissionLevel.BLOCKED)
        val result = pm.enforce(tool, "{}", null)
        assertThat(result).isInstanceOf(PermissionManager.ExecutionResult.Denied::class.java)
    }

    @Test
    fun `Override upgrades BLOCKED tool to SAFE`() = runBlocking {
        val pm = PermissionManager()
        val tool = SimpleTool("blocked", ToolPermissionLevel.BLOCKED)
        pm.setOverride("blocked", ToolPermissionLevel.SAFE)
        assertThat(pm.effectiveLevel(tool)).isEqualTo(ToolPermissionLevel.SAFE)
        val result = pm.enforce(tool, "{}", null)
        assertThat(result).isInstanceOf(PermissionManager.ExecutionResult.Granted::class.java)
    }

    @Test
    fun `Clearing override restores declared level`() = runBlocking {
        val pm = PermissionManager()
        val tool = SimpleTool("safe", ToolPermissionLevel.SAFE)
        pm.setOverride("safe", ToolPermissionLevel.BLOCKED)
        assertThat(pm.effectiveLevel(tool)).isEqualTo(ToolPermissionLevel.BLOCKED)
        pm.setOverride("safe", null)
        assertThat(pm.effectiveLevel(tool)).isEqualTo(ToolPermissionLevel.SAFE)
    }

    private class SimpleTool(
        override val name: String,
        override val permissionLevel: ToolPermissionLevel
    ) : AgentTool {
        override val description: String = ""
        override suspend fun execute(arguments: String): ToolResult =
            ToolResult(success = true, data = "")
    }
}
