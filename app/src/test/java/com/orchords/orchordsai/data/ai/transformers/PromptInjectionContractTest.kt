package com.orchords.orchordsai.data.ai.transformers

import com.orchords.ai.core.MessageRole
import com.orchords.ai.ui.UIMessage
import com.orchords.ai.ui.UIMessagePart
import com.orchords.orchordsai.data.model.Assistant
import com.orchords.orchordsai.data.model.InjectionPosition
import com.orchords.orchordsai.data.model.Lorebook
import com.orchords.orchordsai.data.model.PromptInjection
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PromptInjectionContractTest {
    private val standalone = listOf(InjectionPosition.TOP_OF_CHAT, InjectionPosition.BOTTOM_OF_CHAT, InjectionPosition.AT_DEPTH)
    private val messages = listOf(UIMessage.system("System"), UIMessage.user("Question"), UIMessage.assistant("Answer"), UIMessage.user("Follow-up"))
    private fun id(value: Int) = Uuid.parse("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
    private fun mode(value: Int, priority: Int, role: MessageRole, position: InjectionPosition, content: String = "p$priority") =
        PromptInjection.ModeInjection(id = id(value), name = "Mode $value", enabled = true, priority = priority,
            role = role, position = position, content = content, injectDepth = 3)
    private fun run(modes: List<PromptInjection.ModeInjection>, source: List<UIMessage> = messages) =
        transformMessages(source, Assistant(modeInjectionIds = modes.map { it.id }.toSet(), allowConversationPromptInjection = false), modes, emptyList())
    private fun text(message: UIMessage) = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
    private fun injected(result: List<UIMessage>) = result.filter { it.isSynthetic }.map { it.role to text(it) }

    @Test fun `alternating roles preserve exact descending priority in every standalone position`() {
        standalone.forEach { position ->
            val modes = listOf(mode(1, 10, MessageRole.USER, position), mode(2, 9, MessageRole.ASSISTANT, position),
                mode(3, 8, MessageRole.USER, position), mode(4, 7, MessageRole.ASSISTANT, position))
            val expected = modes.map { it.role to it.content }
            assertEquals(expected, injected(run(modes.reversed())))
            assertEquals(expected, injected(run(listOf(modes[2], modes[0], modes[3], modes[1]))))
        }
    }

    @Test fun `only consecutive same role entries merge and ties use stable IDs`() {
        standalone.forEach { position ->
            val adjacent = listOf(mode(1, 10, MessageRole.USER, position), mode(2, 9, MessageRole.USER, position), mode(3, 8, MessageRole.ASSISTANT, position))
            assertEquals(listOf(MessageRole.USER to "p10\np9", MessageRole.ASSISTANT to "p8"), injected(run(adjacent.reversed())))
            val ties = listOf(mode(1, 5, MessageRole.USER, position, "first"), mode(2, 5, MessageRole.ASSISTANT, position, "second"), mode(3, 5, MessageRole.USER, position, "third"))
            assertEquals(ties.map { it.role to it.content }, injected(run(ties.reversed())))
        }
    }

    @Test fun `different depth groups preserve priority order within their own depth`() {
        val modes = listOf(mode(1, 10, MessageRole.USER, InjectionPosition.AT_DEPTH, "deep-high").copy(injectDepth = 3),
            mode(2, 9, MessageRole.ASSISTANT, InjectionPosition.AT_DEPTH, "deep-low").copy(injectDepth = 3),
            mode(3, 8, MessageRole.USER, InjectionPosition.AT_DEPTH, "near-high").copy(injectDepth = 1),
            mode(4, 7, MessageRole.ASSISTANT, InjectionPosition.AT_DEPTH, "near-low").copy(injectDepth = 1))
        val texts = injected(run(modes.reversed())).map { it.second }
        assertTrue(texts.indexOf("deep-high") < texts.indexOf("deep-low"))
        assertTrue(texts.indexOf("near-high") < texts.indexOf("near-low"))
    }

    @Test fun `system position content has deterministic priority without new system messages`() {
        val modes = listOf(mode(1, 5, MessageRole.SYSTEM, InjectionPosition.BEFORE_SYSTEM_PROMPT, "before-low"),
            mode(2, 10, MessageRole.SYSTEM, InjectionPosition.BEFORE_SYSTEM_PROMPT, "before-high"),
            mode(3, 2, MessageRole.USER, InjectionPosition.AFTER_SYSTEM_PROMPT, "after-low"),
            mode(4, 8, MessageRole.USER, InjectionPosition.AFTER_SYSTEM_PROMPT, "after-high"))
        val result = run(modes)
        assertEquals(1, result.count { it.role == MessageRole.SYSTEM })
        assertEquals("before-high\nbefore-low\nSystem\nafter-high\nafter-low", text(result.first()))
    }

    @Test fun `unsupported standalone roles fail explicitly instead of becoming user messages`() {
        standalone.forEach { position -> listOf(MessageRole.SYSTEM, MessageRole.TOOL).forEach { role ->
            val error = assertThrows(IllegalArgumentException::class.java) { run(listOf(mode(1, 1, role, position, "PRIVATE_BODY_SENTINEL"))) }
            assertTrue(error.message!!.contains("role"))
            assertFalse(error.message!!.contains("PRIVATE_BODY_SENTINEL"))
        } }
        assertEquals("System", text(messages.first()))
    }

    @Test fun `tool role is never accepted as system-position content without protocol identity`() {
        listOf(InjectionPosition.BEFORE_SYSTEM_PROMPT, InjectionPosition.AFTER_SYSTEM_PROMPT).forEach { position ->
            assertThrows(IllegalArgumentException::class.java) { run(listOf(mode(1, 1, MessageRole.TOOL, position))) }
        }
    }

    @Test fun `selected lorebook entries use the same role policy and disabled entries remain inert`() {
        val entry = PromptInjection.RegexInjection(id = id(1), name = "Entry", enabled = true, role = MessageRole.TOOL,
            position = InjectionPosition.TOP_OF_CHAT, content = "Private reference", constantActive = true)
        val book = Lorebook(id = id(2), name = "Book", enabled = true, entries = listOf(entry))
        val assistant = Assistant(lorebookIds = setOf(book.id), allowConversationPromptInjection = false)
        assertThrows(IllegalArgumentException::class.java) { transformMessages(messages, assistant, emptyList(), listOf(book)) }
        assertEquals(messages, transformMessages(messages, assistant, emptyList(), listOf(book.copy(entries = listOf(entry.copy(enabled = false))))))
        assertEquals(messages, transformMessages(messages, assistant.copy(lorebookIds = emptySet()), emptyList(), listOf(book)))
    }

    @Test fun `native serialization preserves supported roles and rejects unknown enum values`() {
        val json = Json { encodeDefaults = true }
        standalone.forEach { position -> listOf(MessageRole.USER, MessageRole.ASSISTANT).forEach { role ->
            val original = mode(1, 10, role, position)
            val restored = json.decodeFromString<PromptInjection.ModeInjection>(json.encodeToString(original))
            assertEquals(original, restored)
            assertEquals(listOf(role to original.content), injected(run(listOf(restored))))
        } }
        val original = mode(1, 1, MessageRole.SYSTEM, InjectionPosition.TOP_OF_CHAT)
        val encoded = json.encodeToString(original)
        assertThrows(IllegalArgumentException::class.java) { run(listOf(json.decodeFromString<PromptInjection.ModeInjection>(encoded))) }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<PromptInjection.ModeInjection>(encoded.replace("\"role\":\"system\"", "\"role\":\"future-role\""))
        }
    }
}
