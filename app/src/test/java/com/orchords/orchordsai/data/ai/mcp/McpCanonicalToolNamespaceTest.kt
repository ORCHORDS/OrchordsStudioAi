package com.orchords.orchordsai.data.ai.mcp

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCanonicalToolNamespaceTest {
    @Test
    fun `server display names with spaces punctuation and unicode become safe canonical namespaces`() {
        val namespace = canonicalMcpServerToolNamespace(
            serverId = Uuid.parse("11111111-2222-3333-4444-555555555555"),
            displayName = "My MCP / 文件 😀",
        )

        assertTrue(namespace.startsWith("MyMCP"))
        assertTrue(namespace.matches(Regex("[A-Za-z0-9]+")))
    }

    @Test
    fun `same display name on different servers cannot collapse into one executable namespace`() {
        val first = canonicalMcpServerToolNamespace(
            serverId = Uuid.parse("11111111-2222-3333-4444-555555555555"),
            displayName = "Shared MCP",
        )
        val second = canonicalMcpServerToolNamespace(
            serverId = Uuid.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
            displayName = "Shared MCP",
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `same server identity is deterministic and empty display keeps a safe prefix`() {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val first = canonicalMcpServerToolNamespace(id, "")
        val second = canonicalMcpServerToolNamespace(id, "")

        assertEquals(first, second)
        assertTrue(first.startsWith("serverZ"))
        assertTrue(first.matches(Regex("[A-Za-z0-9]+")))
    }
}
