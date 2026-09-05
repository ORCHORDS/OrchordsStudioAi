package com.orchords.orchordsai.data.model

import com.orchords.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class LorebookScanDepthTest {
    @Test
    fun `negative scan depth is treated as no history instead of crashing`() {
        val messages = listOf(UIMessage.user("one"), UIMessage.user("two"))

        assertEquals("", extractContextForMatching(messages, -1))
        assertEquals("", extractContextForMatching(messages, Int.MIN_VALUE))
    }

    @Test
    fun `zero scan depth has deterministic empty context semantics`() {
        val messages = listOf(UIMessage.user("one"), UIMessage.user("two"))

        assertEquals("", extractContextForMatching(messages, 0))
    }

    @Test
    fun `ordinary depth keeps only the requested recent messages`() {
        val messages = (0 until 6).map { UIMessage.user("m$it") }

        assertEquals("m2\nm3\nm4\nm5", extractContextForMatching(messages, 4))
    }

    @Test
    fun `depth larger than history returns available history`() {
        val messages = listOf(UIMessage.user("one"), UIMessage.user("two"))

        assertEquals("one\ntwo", extractContextForMatching(messages, 100))
    }

    @Test
    fun `extreme depth is capped before scanning`() {
        val messages = (0 until (MAX_LOREBOOK_SCAN_DEPTH + 10)).map { UIMessage.user("m$it") }
        val expected = messages.takeLast(MAX_LOREBOOK_SCAN_DEPTH).joinToString("\n") { it.toText() }

        assertEquals(expected, extractContextForMatching(messages, Int.MAX_VALUE))
    }
}
