package com.orchords.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAliasPolicyTest {
    @Test
    fun `DeepSeek chat and responses use their documented route-specific name ceilings`() {
        assertEquals(64, ToolNamePolicy.DEEPSEEK_CHAT.maxLength)
        assertEquals(128, ToolNamePolicy.DEEPSEEK_RESPONSES.maxLength)
    }

    @Test
    fun `already safe canonical name stays unchanged`() {
        val aliases = buildProviderToolAliases(
            canonicalNames = listOf("workspace_read_file"),
            policy = ToolNamePolicy.DEEPSEEK_CHAT,
        )
        assertEquals("workspace_read_file", aliases.getValue("workspace_read_file"))
    }

    @Test
    fun `spaces dots slashes and unicode produce provider-safe bounded aliases`() {
        val names = listOf(
            "mcp__My MCP__files.read",
            "mcp/server/files/read",
            "文件.读取",
        )
        val aliases = buildProviderToolAliases(names, ToolNamePolicy.DEEPSEEK_CHAT)

        aliases.values.forEach { alias ->
            assertTrue(alias.isNotBlank())
            assertTrue(alias.length <= 64)
            assertTrue(alias.matches(Regex("[A-Za-z0-9_-]+")))
        }
    }

    @Test
    fun `colliding normalized names remain distinct and stable regardless input order`() {
        val names = listOf(
            "mcp__My MCP__files.read",
            "mcp__My-MCP__files_read",
        )
        val forward = buildProviderToolAliases(names, ToolNamePolicy.DEEPSEEK_CHAT)
        val reverse = buildProviderToolAliases(names.reversed(), ToolNamePolicy.DEEPSEEK_CHAT)

        assertNotEquals(forward.getValue(names[0]), forward.getValue(names[1]))
        assertEquals(forward, reverse)
    }

    @Test
    fun `long common prefixes are bounded without alias collision`() {
        val prefix = "a".repeat(180)
        val first = "$prefix-one"
        val second = "$prefix-two"
        val aliases = buildProviderToolAliases(listOf(first, second), ToolNamePolicy.DEEPSEEK_RESPONSES)

        assertTrue(aliases.getValue(first).length <= 128)
        assertTrue(aliases.getValue(second).length <= 128)
        assertNotEquals(aliases.getValue(first), aliases.getValue(second))
    }
}
