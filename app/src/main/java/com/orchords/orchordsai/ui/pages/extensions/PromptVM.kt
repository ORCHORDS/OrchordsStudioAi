package com.orchords.orchordsai.ui.pages.extensions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orchords.orchordsai.data.datastore.Settings
import com.orchords.orchordsai.data.datastore.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "PromptVM"

internal enum class PromptContentUpdate {
    NONE,
    MODES,
    LOREBOOKS,
    AMBIGUOUS,
}

internal fun classifyPromptContentUpdate(
    before: Settings,
    after: Settings,
): PromptContentUpdate {
    val modesChanged = before.modeInjections != after.modeInjections
    val lorebooksChanged = before.lorebooks != after.lorebooks
    return when {
        modesChanged && lorebooksChanged -> PromptContentUpdate.AMBIGUOUS
        modesChanged -> PromptContentUpdate.MODES
        lorebooksChanged -> PromptContentUpdate.LOREBOOKS
        else -> PromptContentUpdate.NONE
    }
}

class PromptVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    /**
     * PromptPage edits exactly one native content family at a time. Persist only that family so a
     * stale UI snapshot cannot overwrite unrelated assistant/provider/account settings. An
     * ambiguous two-family replacement is rejected instead of guessing which stale snapshot wins.
     */
    fun updateSettings(updated: Settings) {
        val before = settings.value
        viewModelScope.launch {
            when (classifyPromptContentUpdate(before, updated)) {
                PromptContentUpdate.MODES -> settingsStore.replaceModeInjections(updated.modeInjections)
                PromptContentUpdate.LOREBOOKS -> settingsStore.replaceLorebooks(updated.lorebooks)
                PromptContentUpdate.NONE -> Unit
                PromptContentUpdate.AMBIGUOUS -> Log.w(
                    TAG,
                    "Refusing ambiguous prompt-content update that changes modes and lorebooks together",
                )
            }
        }
    }
}
