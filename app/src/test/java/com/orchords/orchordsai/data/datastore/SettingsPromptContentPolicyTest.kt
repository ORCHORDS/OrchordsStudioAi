package com.orchords.orchordsai.data.datastore

import com.orchords.ai.core.MessageRole
import com.orchords.orchordsai.data.model.InjectionPosition
import com.orchords.orchordsai.data.model.Lorebook
import com.orchords.orchordsai.data.model.MAX_LOREBOOK_SCAN_DEPTH
import com.orchords.orchordsai.data.model.PromptInjection
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsPromptContentPolicyTest {
    private fun mode(role: MessageRole = MessageRole.USER) = PromptInjection.ModeInjection(
        id = Uuid.random(),
        name = "Mode",
        enabled = true,
        priority = 10,
        position = InjectionPosition.TOP_OF_CHAT,
        content = "Mode instructions",
        role = role,
    )

    private fun entry(
        scanDepth: Int = 4,
        role: MessageRole = MessageRole.USER,
    ) = PromptInjection.RegexInjection(
        id = Uuid.random(),
        name = "Entry",
        enabled = true,
        priority = 9,
        position = InjectionPosition.TOP_OF_CHAT,
        content = "Reference",
        role = role,
        keywords = listOf("keyword"),
        scanDepth = scanDepth,
    )

    private fun book(entry: PromptInjection.RegexInjection) = Lorebook(
        id = Uuid.random(),
        name = "Book",
        description = "Reference book",
        enabled = true,
        entries = listOf(entry),
    )

    @Test
    fun `valid restored prompt content is returned unchanged`() {
        val settings = Settings(
            modeInjections = listOf(mode()),
            lorebooks = listOf(book(entry(scanDepth = MAX_LOREBOOK_SCAN_DEPTH))),
        )

        assertSame(settings, validateSettingsPromptContent(settings))
    }

    @Test
    fun `restored unsupported standalone roles are rejected before settings replacement`() {
        listOf(MessageRole.SYSTEM, MessageRole.TOOL).forEach { role ->
            val settings = Settings(modeInjections = listOf(mode(role = role)))
            assertThrows(IllegalArgumentException::class.java) {
                validateSettingsPromptContent(settings)
            }
        }
    }

    @Test
    fun `restored lorebook scan depth outside the persisted contract is rejected`() {
        listOf(-1, Int.MIN_VALUE, MAX_LOREBOOK_SCAN_DEPTH + 1, Int.MAX_VALUE).forEach { depth ->
            val settings = Settings(lorebooks = listOf(book(entry(scanDepth = depth))))
            assertThrows(IllegalArgumentException::class.java) {
                validateSettingsPromptContent(settings)
            }
        }
    }

    @Test
    fun `restored lorebook standalone system or tool role is rejected`() {
        listOf(MessageRole.SYSTEM, MessageRole.TOOL).forEach { role ->
            val settings = Settings(lorebooks = listOf(book(entry(role = role))))
            assertThrows(IllegalArgumentException::class.java) {
                validateSettingsPromptContent(settings)
            }
        }
    }
}
