package com.orchords.orchordsai.ui.pages.extensions

import com.orchords.orchordsai.data.datastore.Settings
import com.orchords.orchordsai.data.extensions.BuiltInLibrary
import com.orchords.orchordsai.data.extensions.toLorebook
import com.orchords.orchordsai.data.extensions.toModeInjection
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptContentUpdateTest {
    private val base = Settings(
        modeInjections = emptyList(),
        lorebooks = emptyList(),
    )

    @Test
    fun `unchanged prompt content is a no-op`() {
        assertEquals(PromptContentUpdate.NONE, classifyPromptContentUpdate(base, base.copy()))
    }

    @Test
    fun `mode-only edit routes only to mode persistence`() {
        val updated = base.copy(
            modeInjections = listOf(BuiltInLibrary.catalog.modes.first().toModeInjection()),
        )

        assertEquals(PromptContentUpdate.MODES, classifyPromptContentUpdate(base, updated))
    }

    @Test
    fun `lorebook-only edit routes only to lorebook persistence`() {
        val updated = base.copy(
            lorebooks = listOf(BuiltInLibrary.catalog.lorebooks.first().toLorebook()),
        )

        assertEquals(PromptContentUpdate.LOREBOOKS, classifyPromptContentUpdate(base, updated))
    }

    @Test
    fun `two-family stale snapshot fails closed instead of choosing a winner`() {
        val updated = base.copy(
            modeInjections = listOf(BuiltInLibrary.catalog.modes.first().toModeInjection()),
            lorebooks = listOf(BuiltInLibrary.catalog.lorebooks.first().toLorebook()),
        )

        assertEquals(PromptContentUpdate.AMBIGUOUS, classifyPromptContentUpdate(base, updated))
    }
}
