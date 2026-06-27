package com.kaiser.aiagent.domain.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [ToolCallParser]. Covers:
 *  - canonical {"tool":"X","arguments":{}} shape
 *  - markdown-fenced variants (```json ... ```)
 *  - alternate field names (name/parameters/params/args/function)
 *  - OpenAI wrapped function-call shape
 *  - extra prose around the JSON
 *  - multiple JSON objects (first tool-call-shaped wins)
 *  - malformed JSON (Malformed result)
 *  - no JSON at all (None result)
 */
class ToolCallParserTest {

    private val parser = ToolCallParser()

    @Test
    fun `canonical tool_call shape parses correctly`() {
        val result = parser.parse("""{"tool":"get_time","arguments":{}}""")
        assertThat(result).isInstanceOf(ToolCallParseResult.Found::class.java)
        val found = result as ToolCallParseResult.Found
        assertThat(found.call.tool).isEqualTo("get_time")
        assertThat(found.call.arguments).isEqualTo("{}")
    }

    @Test
    fun `markdown-fenced JSON parses correctly`() {
        val result = parser.parse("```json\n{\"tool\":\"get_time\",\"arguments\":{}}\n```")
        assertThat(result).isInstanceOf(ToolCallParseResult.Found::class.java)
        assertThat((result as ToolCallParseResult.Found).call.tool).isEqualTo("get_time")
    }

    @Test
    fun `name field is accepted as alias for tool`() {
        val result = parser.parse("""{"name":"app_info","arguments":{}}""")
        assertThat(result).isInstanceOf(ToolCallParseResult.Found::class.java)
        assertThat((result as ToolCallParseResult.Found).call.tool).isEqualTo("app_info")
    }

    @Test
    fun `parameters field is accepted as alias for arguments`() {
        val result = parser.parse("""{"tool":"list_files","parameters":{"path":"/Downloads"}}""")
        assertThat(result).isInstanceOf(ToolCallParseResult.Found::class.java)
        val found = result as ToolCallParseResult.Found
        assertThat(found.call.tool).isEqualTo("list_files")
        assertThat(found.call.arguments).contains("path")
        assertThat(found.call.arguments).contains("/Downloads")
    }

    @Test
    fun `params field is accepted as alias for arguments`() {
        val result = parser.parse("""{"tool":"list_files","params":{"path":"/Downloads"}}""")
        assertThat(result).isInstanceOf(ToolCallParseResult.Found::class.java)
    }

    @Test
    fun `args field is accepted as alias for arguments`() {
        val result = parser.parse("""{"tool":"list_files","args":{"path":"/Downloads"}}""")
        assertThat(result).isInstanceOf(ToolCallParseResult.Found::class.java)
    }

    @Test
    fun `OpenAI wrapped function-call shape is unwrapped`() {
        val result = parser.parse(
            """{"function":{"name":"get_time","arguments":"{}"}}"""
        )
        assertThat(result).isInstanceOf(ToolCallParseResult.Found::class.java)
        assertThat((result as ToolCallParseResult.Found).call.tool).isEqualTo("get_time")
    }

    @Test
    fun `extra prose around JSON is tolerated`() {
        val result = parser.parse(
            "Sure! Let me check that for you.\n{\"tool\":\"get_time\",\"arguments\":{}}\nOne moment."
        )
        assertThat(result).isInstanceOf(ToolCallParseResult.Found::class.java)
    }

    @Test
    fun `first tool-call-shaped object wins when multiple present`() {
        val result = parser.parse(
            """{"foo":"bar"} {"tool":"get_time","arguments":{}} {"tool":"app_info","arguments":{}}"""
        )
        assertThat(result).isInstanceOf(ToolCallParseResult.Found::class.java)
        assertThat((result as ToolCallParseResult.Found).call.tool).isEqualTo("get_time")
    }

    @Test
    fun `non-JSON object containing no tool keyword returns None`() {
        val result = parser.parse("Hello, I'm the AI agent. How can I help?")
        assertThat(result).isInstanceOf(ToolCallParseResult.None::class.java)
    }

    @Test
    fun `JSON without tool-keyword returns None`() {
        val result = parser.parse("""{"foo":"bar","baz":[1,2,3]}""")
        assertThat(result).isInstanceOf(ToolCallParseResult.None::class.java)
    }

    @Test
    fun `empty string returns None`() {
        val result = parser.parse("")
        assertThat(result).isInstanceOf(ToolCallParseResult.None::class.java)
    }

    @Test
    fun `blank tool name returns Malformed`() {
        val result = parser.parse("""{"tool":"","arguments":{}}""")
        assertThat(result).isInstanceOf(ToolCallParseResult.Malformed::class.java)
    }
}
