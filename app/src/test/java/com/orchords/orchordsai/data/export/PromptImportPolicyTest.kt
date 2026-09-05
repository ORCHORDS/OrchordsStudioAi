package com.orchords.orchordsai.data.export

import com.orchords.ai.core.MessageRole
import com.orchords.orchordsai.data.model.InjectionPosition
import com.orchords.orchordsai.data.model.Lorebook
import com.orchords.orchordsai.data.model.MAX_LOREBOOK_SCAN_DEPTH
import com.orchords.orchordsai.data.model.PromptInjection
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class PromptImportPolicyTest {
    private fun id(value: Int) = Uuid.parse("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")

    private fun mode(
        role: MessageRole = MessageRole.USER,
        position: InjectionPosition = InjectionPosition.TOP_OF_CHAT,
    ) = PromptInjection.ModeInjection(
        id = id(1),
        name = "Mode",
        enabled = true,
        priority = 10,
        position = position,
        content = "Mode instructions",
        injectDepth = 4,
        role = role,
    )

    private fun entry(
        scanDepth: Int = 4,
        role: MessageRole = MessageRole.USER,
        position: InjectionPosition = InjectionPosition.TOP_OF_CHAT,
    ) = PromptInjection.RegexInjection(
        id = id(2),
        name = "Entry",
        enabled = true,
        priority = 9,
        position = position,
        content = "Reference",
        injectDepth = 4,
        role = role,
        keywords = listOf("keyword"),
        useRegex = false,
        caseSensitive = false,
        scanDepth = scanDepth,
        constantActive = false,
    )

    private fun lorebook(vararg entries: PromptInjection.RegexInjection) = Lorebook(
        id = id(3),
        name = "Book",
        description = "Reference book",
        enabled = true,
        entries = entries.toList(),
    )

    @Test
    fun `native standalone imports preserve user and assistant roles`() {
        listOf(MessageRole.USER, MessageRole.ASSISTANT).forEach { role ->
            val source = mode(role = role)
            val imported = ModeInjectionSerializer.decodeForImport(
                ModeInjectionSerializer.exportToJson(source)
            )
            assertEquals(role, imported.role)
            assertEquals(source.position, imported.position)
            assertEquals(source.content, imported.content)
        }
    }

    @Test
    fun `native standalone imports reject system and tool roles before activation`() {
        listOf(MessageRole.SYSTEM, MessageRole.TOOL).forEach { role ->
            val source = mode(role = role)
            assertThrows(IllegalArgumentException::class.java) {
                ModeInjectionSerializer.decodeForImport(ModeInjectionSerializer.exportToJson(source))
            }
        }
    }

    @Test
    fun `system splice imports reject tool but preserve supported stored roles`() {
        listOf(InjectionPosition.BEFORE_SYSTEM_PROMPT, InjectionPosition.AFTER_SYSTEM_PROMPT).forEach { position ->
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT).forEach { role ->
                val source = mode(role = role, position = position)
                assertEquals(role, ModeInjectionSerializer.decodeForImport(ModeInjectionSerializer.exportToJson(source)).role)
            }
            assertThrows(IllegalArgumentException::class.java) {
                val source = mode(role = MessageRole.TOOL, position = position)
                ModeInjectionSerializer.decodeForImport(ModeInjectionSerializer.exportToJson(source))
            }
        }
    }

    @Test
    fun `native lorebook import accepts documented scan depth boundaries unchanged`() {
        listOf(0, 4, MAX_LOREBOOK_SCAN_DEPTH).forEach { depth ->
            val source = lorebook(entry(scanDepth = depth))
            val imported = LorebookSerializer.decodeForImport(
                LorebookSerializer.exportToJson(source),
                fileName = "book",
            )
            assertEquals(depth, imported.entries.single().scanDepth)
        }
    }

    @Test
    fun `native lorebook import rejects negative and excessive scan depths before activation`() {
        listOf(-1, Int.MIN_VALUE, MAX_LOREBOOK_SCAN_DEPTH + 1, Int.MAX_VALUE).forEach { depth ->
            val source = lorebook(entry(scanDepth = depth))
            assertThrows(IllegalArgumentException::class.java) {
                LorebookSerializer.decodeForImport(
                    LorebookSerializer.exportToJson(source),
                    fileName = "book",
                )
            }
        }
    }

    @Test
    fun `native lorebook entries use the same standalone role policy`() {
        listOf(MessageRole.SYSTEM, MessageRole.TOOL).forEach { role ->
            val source = lorebook(entry(role = role))
            assertThrows(IllegalArgumentException::class.java) {
                LorebookSerializer.decodeForImport(
                    LorebookSerializer.exportToJson(source),
                    fileName = "book",
                )
            }
        }
    }

    @Test
    fun `unknown future native role fails closed during import decode`() {
        val encoded = ModeInjectionSerializer.exportToJson(mode())
            .replace("\"role\":\"user\"", "\"role\":\"future-role\"")
        assertThrows(SerializationException::class.java) {
            ModeInjectionSerializer.decodeForImport(encoded)
        }
    }

    @Test
    fun `external lorebook invalid scan depth is a validation error not silent normalization`() {
        val external = """
            {
              "entries": {
                "0": {
                  "key": ["keyword"],
                  "content": "Reference",
                  "comment": "Bad depth",
                  "constant": false,
                  "position": 2,
                  "order": 100,
                  "disable": false,
                  "depth": 4,
                  "scanDepth": -1,
                  "caseSensitive": false
                }
              }
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            LorebookSerializer.decodeForImport(external, fileName = "external")
        }
    }

    @Test
    fun `invalid lorebook import does not change a separate valid lorebook value`() {
        val valid = lorebook(entry(scanDepth = 4))
        val invalid = lorebook(entry(scanDepth = -1))

        assertThrows(IllegalArgumentException::class.java) {
            LorebookSerializer.decodeForImport(
                LorebookSerializer.exportToJson(invalid),
                fileName = "invalid",
            )
        }
        assertEquals(4, valid.entries.single().scanDepth)
        assertEquals(MessageRole.USER, valid.entries.single().role)
    }
}
