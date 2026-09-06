package com.orchords.orchordsai.data.ai.transformers

import com.orchords.ai.ui.UIMessage
import com.orchords.ai.ui.UIMessagePart
import com.orchords.orchordsai.data.model.Assistant
import com.orchords.orchordsai.data.model.PromptInjection
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectionBudgetPolicyTest {
    @Test
    fun `activation retains only the 64 highest priority entries`() {
        val modes = (0..64).map { index ->
            mode(priority = index, content = "mode-$index")
        }
        val assistant = Assistant(modeInjectionIds = modes.map { it.id }.toSet())

        val result = transformMessages(
            messages = listOf(UIMessage.system("system")),
            assistant = assistant,
            modeInjections = modes,
            lorebooks = emptyList(),
        )
        val text = result
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }

        assertTrue(text.contains("mode-64"))
        assertTrue(text.contains("mode-1"))
        assertFalse(text.contains("mode-0"))
    }

    @Test
    fun `oversize high priority entry is omitted while smaller entries survive`() {
        val oversized = mode(
            priority = 100,
            content = "x".repeat(InjectionBudgetPolicy.MAX_ENTRY_CHARS + 1),
        )
        val smaller = mode(priority = 10, content = "keep")

        val result = selectBudgetedInjections(sequenceOf(oversized, smaller))

        assertEquals(listOf(smaller.id), result.injections.map { it.id })
        assertEquals(1, result.omittedCount)
        assertEquals(InjectionOmissionReason.ENTRY_CHARS, result.omissions.single().reason)
    }

    @Test
    fun `entry UTF8 bytes are bounded independently from character count`() {
        val oversizedUtf8 = mode(
            priority = 10,
            content = "€".repeat(11_000),
        )

        val result = selectBudgetedInjections(sequenceOf(oversizedUtf8))

        assertTrue(result.injections.isEmpty())
        assertEquals(InjectionOmissionReason.ENTRY_UTF8_BYTES, result.omissions.single().reason)
    }

    @Test
    fun `aggregate character budget skips content before concatenation`() {
        val modes = (0 until 5).map { index ->
            mode(priority = 100 - index, content = "x".repeat(16_000))
        }

        val result = selectBudgetedInjections(modes.asSequence())

        assertEquals(4, result.injections.size)
        assertEquals(modes.take(4).map { it.id }, result.injections.map { it.id })
        assertEquals(InjectionOmissionReason.AGGREGATE_CHARS, result.omissions.single().reason)
    }

    @Test
    fun `aggregate UTF8 byte budget is enforced for multibyte content`() {
        val modes = (0 until 5).map { index ->
            mode(priority = 100 - index, content = "€".repeat(10_000))
        }

        val result = selectBudgetedInjections(modes.asSequence())

        assertEquals(4, result.injections.size)
        assertEquals(InjectionOmissionReason.AGGREGATE_UTF8_BYTES, result.omissions.single().reason)
    }

    @Test
    fun `thousands of candidates keep only bounded highest priority references`() {
        val modes = (0 until 10_000).map { index ->
            mode(priority = index, content = "m$index")
        }

        val result = selectBudgetedInjections(modes.asSequence())

        assertEquals(InjectionBudgetPolicy.MAX_ACTIVE_ENTRIES, result.injections.size)
        assertEquals(9_999, result.injections.first().priority)
        assertEquals(9_936, result.injections.last().priority)
        assertEquals(9_936, result.omittedCount)
        assertEquals(InjectionBudgetPolicy.MAX_OMISSION_DETAILS, result.omissions.size)
    }

    @Test
    fun `equal priority selection is stable regardless of source order`() {
        val modes = (0 until 70).map { mode(priority = 7, content = "same") }

        val forward = selectBudgetedInjections(modes.asSequence()).injections.map { it.id }
        val reversed = selectBudgetedInjections(modes.asReversed().asSequence()).injections.map { it.id }

        assertEquals(forward, reversed)
    }

    private fun mode(priority: Int, content: String): PromptInjection.ModeInjection =
        PromptInjection.ModeInjection(
            id = Uuid.random(),
            priority = priority,
            content = content,
        )
}
