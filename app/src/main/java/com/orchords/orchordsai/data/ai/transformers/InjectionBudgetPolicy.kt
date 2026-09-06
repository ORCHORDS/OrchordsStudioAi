package com.orchords.orchordsai.data.ai.transformers

import com.orchords.orchordsai.data.model.PromptInjection
import kotlin.uuid.Uuid

internal object InjectionBudgetPolicy {
    const val MAX_ACTIVE_ENTRIES = 64
    const val MAX_ENTRY_CHARS = 16_384
    const val MAX_ENTRY_UTF8_BYTES = 32_768
    const val MAX_AGGREGATE_CHARS = 65_536
    const val MAX_AGGREGATE_UTF8_BYTES = 131_072
    const val MAX_OMISSION_DETAILS = 64
}

internal enum class InjectionOmissionReason {
    ENTRY_CHARS,
    ENTRY_UTF8_BYTES,
    ENTRY_COUNT,
    AGGREGATE_CHARS,
    AGGREGATE_UTF8_BYTES,
}

internal data class InjectionOmission(
    val id: Uuid,
    val reason: InjectionOmissionReason,
    val contentChars: Int,
    val contentUtf8Bytes: Int?,
)

internal data class BudgetedInjections(
    val injections: List<PromptInjection>,
    val omittedCount: Int,
    val omissions: List<InjectionOmission>,
)

private data class MeasuredInjection(
    val injection: PromptInjection,
    val chars: Int,
    val utf8Bytes: Int,
)

private val injectionOrder = compareByDescending<MeasuredInjection> { it.injection.priority }
    .thenBy { it.injection.id.toString() }

/**
 * Selects bounded injection content without first materializing or sorting the full active set.
 * Only references to the highest-priority [InjectionBudgetPolicy.MAX_ACTIVE_ENTRIES] candidates
 * are retained while scanning; content is not concatenated until after aggregate limits pass.
 */
internal fun selectBudgetedInjections(
    candidates: Sequence<PromptInjection>,
): BudgetedInjections {
    val top = ArrayList<MeasuredInjection>(InjectionBudgetPolicy.MAX_ACTIVE_ENTRIES)
    val omissions = ArrayList<InjectionOmission>(InjectionBudgetPolicy.MAX_OMISSION_DETAILS)
    var omittedCount = 0

    fun omit(measured: MeasuredInjection, reason: InjectionOmissionReason) {
        omittedCount++
        if (omissions.size < InjectionBudgetPolicy.MAX_OMISSION_DETAILS) {
            omissions += InjectionOmission(
                id = measured.injection.id,
                reason = reason,
                contentChars = measured.chars,
                contentUtf8Bytes = measured.utf8Bytes,
            )
        }
    }

    fun omit(
        injection: PromptInjection,
        reason: InjectionOmissionReason,
        chars: Int,
        utf8Bytes: Int?,
    ) {
        omittedCount++
        if (omissions.size < InjectionBudgetPolicy.MAX_OMISSION_DETAILS) {
            omissions += InjectionOmission(
                id = injection.id,
                reason = reason,
                contentChars = chars,
                contentUtf8Bytes = utf8Bytes,
            )
        }
    }

    candidates.forEach { injection ->
        val chars = injection.content.length
        if (chars > InjectionBudgetPolicy.MAX_ENTRY_CHARS) {
            omit(injection, InjectionOmissionReason.ENTRY_CHARS, chars, null)
            return@forEach
        }

        val utf8Bytes = injection.content.utf8LengthAtMost(InjectionBudgetPolicy.MAX_ENTRY_UTF8_BYTES)
        if (utf8Bytes == null) {
            omit(injection, InjectionOmissionReason.ENTRY_UTF8_BYTES, chars, null)
            return@forEach
        }

        val measured = MeasuredInjection(injection, chars, utf8Bytes)
        val insertionIndex = top.binarySearch(measured, injectionOrder).let { found ->
            if (found >= 0) found else -found - 1
        }
        top.add(insertionIndex, measured)
        if (top.size > InjectionBudgetPolicy.MAX_ACTIVE_ENTRIES) {
            omit(top.removeAt(top.lastIndex), InjectionOmissionReason.ENTRY_COUNT)
        }
    }

    val retained = ArrayList<PromptInjection>(top.size)
    var aggregateChars = 0
    var aggregateUtf8Bytes = 0

    top.forEach { measured ->
        // Charge a conservative separator for every retained entry after the first. Some
        // positions serialize without a separator, so this can only make the ceiling safer.
        val separatorChars = if (retained.isEmpty()) 0 else 1
        val separatorBytes = separatorChars
        if (aggregateChars + separatorChars + measured.chars > InjectionBudgetPolicy.MAX_AGGREGATE_CHARS) {
            omit(measured, InjectionOmissionReason.AGGREGATE_CHARS)
            return@forEach
        }
        if (aggregateUtf8Bytes + separatorBytes + measured.utf8Bytes > InjectionBudgetPolicy.MAX_AGGREGATE_UTF8_BYTES) {
            omit(measured, InjectionOmissionReason.AGGREGATE_UTF8_BYTES)
            return@forEach
        }

        retained += measured.injection
        aggregateChars += separatorChars + measured.chars
        aggregateUtf8Bytes += separatorBytes + measured.utf8Bytes
    }

    return BudgetedInjections(
        injections = retained,
        omittedCount = omittedCount,
        omissions = omissions,
    )
}

/** Returns null as soon as the UTF-8 byte ceiling is exceeded, without allocating a byte array. */
private fun String.utf8LengthAtMost(limit: Int): Int? {
    var bytes = 0
    var index = 0
    while (index < length) {
        val char = this[index]
        val nextBytes = when {
            char.code <= 0x7F -> 1
            char.code <= 0x7FF -> 2
            char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                index++
                4
            }
            else -> 3
        }
        bytes += nextBytes
        if (bytes > limit) return null
        index++
    }
    return bytes
}
