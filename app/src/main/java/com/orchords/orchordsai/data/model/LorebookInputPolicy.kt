package com.orchords.orchordsai.data.model

/** Parse only lorebook scan-depth values that can become valid persisted state. */
internal fun parseLorebookScanDepthOrNull(input: String): Int? =
    input.toIntOrNull()?.takeIf { it in 0..MAX_LOREBOOK_SCAN_DEPTH }
