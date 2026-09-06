package com.orchords.orchordsai.data.datastore

import com.orchords.orchordsai.data.ai.transformers.requireSupportedInjectionRole
import com.orchords.orchordsai.data.model.Lorebook
import com.orchords.orchordsai.data.model.MAX_LOREBOOK_SCAN_DEPTH
import com.orchords.orchordsai.data.model.PromptInjection

internal fun validateModeInjectionsForPersistence(
    modeInjections: List<PromptInjection.ModeInjection>,
) {
    modeInjections.forEach { injection ->
        requireSupportedInjectionRole(injection.position, injection.role)
    }
}

internal fun validateLorebooksForPersistence(lorebooks: List<Lorebook>) {
    lorebooks.forEach { lorebook ->
        lorebook.entries.forEach { entry ->
            requireSupportedInjectionRole(entry.position, entry.role)
            require(entry.scanDepth in 0..MAX_LOREBOOK_SCAN_DEPTH) {
                "Lorebook scan depth must be between 0 and $MAX_LOREBOOK_SCAN_DEPTH"
            }
        }
    }
}

internal fun validateSettingsPromptContent(settings: Settings): Settings {
    validateModeInjectionsForPersistence(settings.modeInjections)
    validateLorebooksForPersistence(settings.lorebooks)
    return settings
}
