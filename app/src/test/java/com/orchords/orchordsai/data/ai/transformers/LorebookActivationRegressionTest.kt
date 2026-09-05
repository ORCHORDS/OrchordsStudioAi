package com.orchords.orchordsai.data.ai.transformers

import com.orchords.ai.ui.UIMessage
import com.orchords.orchordsai.data.model.Assistant
import com.orchords.orchordsai.data.model.Lorebook
import com.orchords.orchordsai.data.model.PromptInjection
import com.orchords.orchordsai.data.model.extractContextForMatching
import com.orchords.orchordsai.data.model.isTriggered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookActivationRegressionTest {
    @Test
    fun `blank literal and regex keywords never become implicit always-on triggers`() {
        listOf(false, true).forEach { regex ->
            val entry = PromptInjection.RegexInjection(keywords = listOf("", "  ", "\t"), useRegex = regex)
            assertFalse(entry.isTriggered("Unrelated user request"))
            assertTrue(entry.copy(keywords = entry.keywords + "request").isTriggered("User request"))
        }
    }

    @Test
    fun `explicit constant activation and disable controls retain their precedence`() {
        val entry = PromptInjection.RegexInjection(constantActive = true)
        assertTrue(entry.isTriggered(""))
        assertFalse(entry.copy(enabled = false).isTriggered(""))
    }

    @Test
    fun `negative and zero legacy scan depths return empty context instead of crashing`() {
        val messages = listOf(UIMessage.user("keyword"))
        assertEquals("", extractContextForMatching(messages, -1))
        assertEquals("", extractContextForMatching(messages, Int.MIN_VALUE))
        assertEquals("", extractContextForMatching(messages, 0))
    }

    @Test
    fun `synthetic and system messages neither trigger lorebooks nor consume scan depth`() {
        val messages = listOf(
            UIMessage.user("earlier user"),
            UIMessage.system("system sentinel"),
            UIMessage.assistant("actual reply"),
            UIMessage.user("synthetic sentinel").copy(isSynthetic = true),
        )
        val context = extractContextForMatching(messages, 2)
        assertTrue(context.contains("earlier user"))
        assertTrue(context.contains("actual reply"))
        assertFalse(context.contains("sentinel"))
        assertEquals("actual reply", extractContextForMatching(messages, 1))
    }

    @Test
    fun `extreme depth is bounded to 256 genuine turns in chronological order`() {
        val messages = (0 until 300).map { UIMessage.user("m$it") }
        val expected = (44 until 300).joinToString("\n") { "m$it" }
        assertEquals(expected, extractContextForMatching(messages, Int.MAX_VALUE))
    }

    @Test
    fun `ordinary literal case and valid regex semantics remain supported`() {
        val entry = PromptInjection.RegexInjection(keywords = listOf("Release"))
        assertTrue(entry.isTriggered("release review"))
        assertFalse(entry.copy(caseSensitive = true).isTriggered("release review"))
        assertTrue(entry.copy(keywords = listOf("release [0-9]+"), useRegex = true).isTriggered("Release 42"))
        assertFalse(entry.copy(keywords = listOf("["), useRegex = true).isTriggered("anything"))
    }

    @Test
    fun `collection uses only selected enabled books and genuine matching conversation`() {
        val entry = PromptInjection.RegexInjection(keywords = listOf("migration rehearsal"), content = "entry guidance")
        val book = Lorebook(name = "Storage", entries = listOf(entry))
        val assistant = Assistant(lorebookIds = setOf(book.id))
        val synthetic = listOf(UIMessage.user("migration rehearsal").copy(isSynthetic = true))
        assertTrue(collectInjections(synthetic, assistant, emptyList(), listOf(book)).isEmpty())
        val actual = listOf(UIMessage.user("Run a migration rehearsal"))
        assertEquals(listOf(entry), collectInjections(actual, assistant, emptyList(), listOf(book)))
        assertTrue(collectInjections(actual, Assistant(), emptyList(), listOf(book)).isEmpty())
        assertTrue(collectInjections(actual, assistant, emptyList(), listOf(book.copy(enabled = false))).isEmpty())
    }
}
