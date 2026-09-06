package com.orchords.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAliasPolicyTest {
    @Test
    fun `route policies keep documented and conservative ceilings distinct`() {
        assertEquals(64, ToolNamePolicy.OPENAI_CHAT.maxLength)
        assertEquals(64, ToolNamePolicy.OPENAI_RESPONSES.maxLength)
        assertEquals(64, ToolNamePolicy.OPENAI_COMPATIBLE.maxLength)
        assertEquals(64, ToolNamePolicy.DEEPSEEK_CHAT.maxLength)
        assertEquals(128, ToolNamePolicy.DEEPSEEK_RESPONSES.maxLength)
        assertEquals(128, ToolNamePolicy.GEMINI.maxLength)
        assertEquals(64, ToolNamePolicy.CLAUDE.maxLength)
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
            "tool/😀/read",
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
    fun `safe canonical names are reserved before generated aliases`() {
        val unsafe = "files.read"
        val generatedWhenAlone = buildProviderToolAliases(
            listOf(unsafe),
            ToolNamePolicy.DEEPSEEK_CHAT,
        ).getValue(unsafe)

        val aliases = buildProviderToolAliases(
            listOf(unsafe, generatedWhenAlone),
            ToolNamePolicy.DEEPSEEK_CHAT,
        )

        assertEquals(generatedWhenAlone, aliases.getValue(generatedWhenAlone))
        assertNotEquals(aliases.getValue(unsafe), aliases.getValue(generatedWhenAlone))
        assertEquals(aliases.size, aliases.values.toSet().size)
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

    @Test
    fun `aliases remain deterministic and unique across a large hostile set`() {
        val names = buildList {
            repeat(200) { index ->
                add("mcp__同じ server/$index/" + "x".repeat(180))
            }
        }
        val aliases = buildProviderToolAliases(names, ToolNamePolicy.CLAUDE)

        assertEquals(names.size, aliases.size)
        assertEquals(names.size, aliases.values.toSet().size)
        aliases.values.forEach { alias ->
            assertTrue(alias.length <= 64)
            assertTrue(alias.matches(Regex("[A-Za-z0-9_-]+")))
        }
    }
}
