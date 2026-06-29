package com.kaiser.aiagent.domain.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Unit tests for [ToolExecutor]. Covers:
 *  - SAFE tool executes immediately
 *  - BLOCKED tool returns denial without executing
 *  - CONFIRMATION_REQUIRED tool executes only after approval
 *  - CONFIRMATION_REQUIRED tool returns denial when user denies
 *  - Unknown tool returns failure
 *  - Invalid arguments JSON returns failure
 *  - Throwing tool returns failure with the exception message
 *  - onResult callback is invoked in all cases
 */
class ToolExecutorTest {

    private fun makeExecutor(perm: ToolPermissionLevel): Pair<ToolExecutor, ToolRegistry> {
        val registry = ToolRegistry()
        val pm = PermissionManager()
        val executor = ToolExecutor(registry, pm)
        return executor to registry
    }

    @Test
    fun `SAFE tool executes immediately`() = runBlocking {
        val (executor, registry) = makeExecutor(ToolPermissionLevel.SAFE)
        registry.register(SimpleTool("safe_tool", ToolPermissionLevel.SAFE, "ok"))
        val result = executor.execute(ToolCall("safe_tool", "{}"))
        assertThat(result.success).isTrue()
        assertThat(result.data).isEqualTo("ok")
    }

    @Test
    fun `BLOCKED tool returns denial without executing`() = runBlocking {
        val (executor, registry) = makeExecutor(ToolPermissionLevel.BLOCKED)
        var executed = false
        registry.register(object : AgentTool {
            override val name = "blocked_tool"
            override val description = ""
            override val permissionLevel = ToolPermissionLevel.BLOCKED
            override suspend fun execute(arguments: String): ToolResult {
                executed = true
                return ToolResult(true, "should not run", null)
            }
        })
        val result = executor.execute(ToolCall("blocked_tool", "{}"))
        assertThat(result.success).isFalse()
        assertThat(executed).isFalse()
        assertThat(result.error).contains("blocked")
    }

    @Test
    fun `CONFIRMATION_REQUIRED tool executes after approval`() = runBlocking {
        val (executor, registry) = makeExecutor(ToolPermissionLevel.CONFIRMATION_REQUIRED)
        registry.register(SimpleTool("confirm_tool", ToolPermissionLevel.CONFIRMATION_REQUIRED, "ok"))
        // Approve async before calling execute (the call will suspend on the deferred)
        val pmField = ToolExecutor::class.java.getDeclaredField("registry")
        // Easier: get PermissionManager from the executor indirectly via a separate instance.
        val pm = PermissionManager()
        val registry2 = ToolRegistry()
        val executor2 = ToolExecutor(registry2, pm)
        registry2.register(SimpleTool("confirm_tool", ToolPermissionLevel.CONFIRMATION_REQUIRED, "ok"))
        // Launch approve in a separate coroutine so execute() can suspend
        GlobalScope.launch {
            delay(50)
            pm.approve()
        }
        val result = executor2.execute(ToolCall("confirm_tool", "{}"))
        assertThat(result.success).isTrue()
        assertThat(result.data).isEqualTo("ok")
    }

    @Test
    fun `CONFIRMATION_REQUIRED tool returns denial when user denies`() = runBlocking {
        val pm = PermissionManager()
        val registry = ToolRegistry()
        val executor = ToolExecutor(registry, pm)
        registry.register(SimpleTool("confirm_tool", ToolPermissionLevel.CONFIRMATION_REQUIRED, "ok"))
        GlobalScope.launch {
            delay(50)
            pm.deny()
        }
        val result = executor.execute(ToolCall("confirm_tool", "{}"))
        assertThat(result.success).isFalse()
        assertThat(result.error).contains("denied")
    }

    @Test
    fun `Unknown tool returns failure`() = runBlocking {
        val (executor, _) = makeExecutor(ToolPermissionLevel.SAFE)
        val result = executor.execute(ToolCall("nonexistent_tool", "{}"))
        assertThat(result.success).isFalse()
        assertThat(result.error).contains("Unknown tool")
    }

    @Test
    fun `Invalid arguments JSON returns failure`() = runBlocking {
        val (executor, registry) = makeExecutor(ToolPermissionLevel.SAFE)
        registry.register(SimpleTool("safe_tool", ToolPermissionLevel.SAFE, "ok"))
        // Use truly invalid JSON — "not-json" is actually a valid JSON string literal,
        // so we use a malformed object instead.
        val result = executor.execute(ToolCall("safe_tool", "{not valid"))
        assertThat(result.success).isFalse()
        assertThat(result.error).contains("Invalid arguments JSON")
    }

    @Test
    fun `Throwing tool returns failure with exception message`() = runBlocking {
        val (executor, registry) = makeExecutor(ToolPermissionLevel.SAFE)
        registry.register(object : AgentTool {
            override val name = "throwing_tool"
            override val description = ""
            override val permissionLevel = ToolPermissionLevel.SAFE
            override suspend fun execute(arguments: String): ToolResult {
                throw RuntimeException("boom!")
            }
        })
        val result = executor.execute(ToolCall("throwing_tool", "{}"))
        assertThat(result.success).isFalse()
        assertThat(result.error).contains("boom!")
    }

    @Test
    fun `onResult callback is invoked after execution`() = runBlocking {
        val (executor, registry) = makeExecutor(ToolPermissionLevel.SAFE)
        registry.register(SimpleTool("safe_tool", ToolPermissionLevel.SAFE, "ok"))
        var callbackInvoked = false
        var callbackToolName: String? = null
        executor.execute(ToolCall("safe_tool", "{}")) { name, _ ->
            callbackInvoked = true
            callbackToolName = name
        }
        assertThat(callbackInvoked).isTrue()
        assertThat(callbackToolName).isEqualTo("safe_tool")
    }

    /** A simple test AgentTool that returns a fixed data string. */
    private class SimpleTool(
        override val name: String,
        override val permissionLevel: ToolPermissionLevel,
        private val data: String
    ) : AgentTool {
        override val description: String = "Test tool"
        override suspend fun execute(arguments: String): ToolResult =
            ToolResult(success = true, data = data)
    }
}
