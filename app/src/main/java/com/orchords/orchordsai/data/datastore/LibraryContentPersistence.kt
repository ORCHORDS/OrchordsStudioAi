package com.orchords.orchordsai.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.orchords.orchordsai.data.extensions.appendMissingById
import com.orchords.orchordsai.data.model.Lorebook
import com.orchords.orchordsai.data.model.PromptInjection
import com.orchords.orchordsai.utils.JsonInstant

data class LibraryContentReceipt(val addedModes: Int, val addedLorebooks: Int)

/** Read/merge/write only these two keys inside the existing DataStore transaction. */
internal suspend fun appendLibraryContent(
    store: DataStore<Preferences>,
    modes: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
): LibraryContentReceipt {
    require(modes.map { it.id }.distinct().size == modes.size)
    require(lorebooks.map { it.id }.distinct().size == lorebooks.size)
    var receipt = LibraryContentReceipt(0, 0)
    store.edit { preferences ->
        // Corrupt existing content fails the transaction; it is not an empty user library.
        val currentModes = JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(
            preferences[SettingsStore.MODE_INJECTIONS] ?: "[]"
        )
        val currentBooks = JsonInstant.decodeFromString<List<Lorebook>>(
            preferences[SettingsStore.LOREBOOKS] ?: "[]"
        )
        val mergedModes = appendMissingById(currentModes, modes) { it.id.toString() }
        val mergedBooks = appendMissingById(currentBooks, lorebooks) { it.id.toString() }
        validateModeInjectionsForPersistence(mergedModes)
        validateLorebooksForPersistence(mergedBooks)
        preferences[SettingsStore.MODE_INJECTIONS] = JsonInstant.encodeToString(mergedModes)
        preferences[SettingsStore.LOREBOOKS] = JsonInstant.encodeToString(mergedBooks)
        receipt = LibraryContentReceipt(mergedModes.size - currentModes.size, mergedBooks.size - currentBooks.size)
    }
    // Returning before edit commits, or optimistically changing SettingsFlow, would be false success.
    return receipt
}

/**
 * Mutate only the persisted mode list inside DataStore's serialized read-modify-write transaction.
 * The caller supplies a pure transformation; corrupt content aborts instead of becoming an empty list.
 */
internal suspend fun updateModeInjections(
    store: DataStore<Preferences>,
    transform: (List<PromptInjection.ModeInjection>) -> List<PromptInjection.ModeInjection>,
) {
    store.edit { preferences ->
        val current = JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(
            preferences[SettingsStore.MODE_INJECTIONS] ?: "[]"
        )
        val updated = transform(current)
        require(updated.map { it.id }.distinct().size == updated.size) {
            "Mode injection IDs must be unique"
        }
        validateModeInjectionsForPersistence(updated)
        preferences[SettingsStore.MODE_INJECTIONS] = JsonInstant.encodeToString(updated)
    }
}

/**
 * Mutate only the persisted lorebook list inside DataStore's serialized read-modify-write transaction.
 * Unrelated preferences and concurrent mode changes cannot be overwritten by this operation.
 */
internal suspend fun updateLorebooks(
    store: DataStore<Preferences>,
    transform: (List<Lorebook>) -> List<Lorebook>,
) {
    store.edit { preferences ->
        val current = JsonInstant.decodeFromString<List<Lorebook>>(
            preferences[SettingsStore.LOREBOOKS] ?: "[]"
        )
        val updated = transform(current)
        require(updated.map { it.id }.distinct().size == updated.size) {
            "Lorebook IDs must be unique"
        }
        validateLorebooksForPersistence(updated)
        preferences[SettingsStore.LOREBOOKS] = JsonInstant.encodeToString(updated)
    }
}
