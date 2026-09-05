package com.orchords.orchordsai.data.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.orchords.orchordsai.data.extensions.BuiltInLibrary
import com.orchords.orchordsai.data.extensions.toLorebook
import com.orchords.orchordsai.data.extensions.toModeInjection
import com.orchords.orchordsai.data.model.Lorebook
import com.orchords.orchordsai.data.model.PromptInjection
import com.orchords.orchordsai.utils.JsonInstant
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryContentMutationTest {
    @Test
    fun `mode mutation preserves lorebooks assistant state and unrelated preferences`() = runBlocking {
        val root = Files.createTempDirectory("mode-mutation-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            root.resolve("settings.preferences_pb")
        }
        try {
            val mode = BuiltInLibrary.catalog.modes.first().toModeInjection()
            val lorebook = BuiltInLibrary.catalog.lorebooks.first().toLorebook()
            val sentinel = stringPreferencesKey("prompt-mutation-sentinel")
            store.edit {
                it[SettingsStore.MODE_INJECTIONS] = JsonInstant.encodeToString(listOf(mode))
                it[SettingsStore.LOREBOOKS] = JsonInstant.encodeToString(listOf(lorebook))
                it[SettingsStore.ASSISTANTS] = "assistant-state-must-survive"
                it[sentinel] = "unrelated-must-survive"
            }

            updateModeInjections(store) { current ->
                current.map { if (it.id == mode.id) it.copy(content = "edited safely") else it }
            }

            val result = store.data.first()
            val modes = JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(
                requireNotNull(result[SettingsStore.MODE_INJECTIONS])
            )
            val books = JsonInstant.decodeFromString<List<Lorebook>>(
                requireNotNull(result[SettingsStore.LOREBOOKS])
            )
            assertEquals("edited safely", modes.single().content)
            assertEquals(listOf(lorebook), books)
            assertEquals("assistant-state-must-survive", result[SettingsStore.ASSISTANTS])
            assertEquals("unrelated-must-survive", result[sentinel])
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent mode and lorebook mutations serialize without lost updates`() = runBlocking {
        val root = Files.createTempDirectory("prompt-concurrent-mutation-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) {
            root.resolve("settings.preferences_pb")
        }
        try {
            val mode = BuiltInLibrary.catalog.modes.first().toModeInjection()
            val lorebook = BuiltInLibrary.catalog.lorebooks.first().toLorebook()
            store.edit {
                it[SettingsStore.MODE_INJECTIONS] = JsonInstant.encodeToString(listOf(mode))
                it[SettingsStore.LOREBOOKS] = JsonInstant.encodeToString(listOf(lorebook))
            }

            listOf(
                async {
                    updateModeInjections(store) { current ->
                        current.map { if (it.id == mode.id) it.copy(name = "Concurrent mode edit") else it }
                    }
                },
                async {
                    updateLorebooks(store) { current ->
                        current.map { if (it.id == lorebook.id) it.copy(name = "Concurrent lorebook edit") else it }
                    }
                },
            ).awaitAll()

            val result = store.data.first()
            val modes = JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(
                requireNotNull(result[SettingsStore.MODE_INJECTIONS])
            )
            val books = JsonInstant.decodeFromString<List<Lorebook>>(
                requireNotNull(result[SettingsStore.LOREBOOKS])
            )
            assertEquals("Concurrent mode edit", modes.single().name)
            assertEquals("Concurrent lorebook edit", books.single().name)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
