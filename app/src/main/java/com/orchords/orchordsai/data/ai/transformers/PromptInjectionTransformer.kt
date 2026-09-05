package com.orchords.orchordsai.data.ai.transformers

import com.orchords.ai.core.MessageRole
import com.orchords.ai.ui.UIMessage
import com.orchords.ai.ui.UIMessagePart
import com.orchords.orchordsai.data.model.Assistant
import com.orchords.orchordsai.data.model.InjectionPosition
import com.orchords.orchordsai.data.model.PromptInjection
import com.orchords.orchordsai.data.model.Lorebook
import com.orchords.orchordsai.data.model.extractContextForMatching
import com.orchords.orchordsai.data.model.isTriggered
import kotlin.uuid.Uuid

/**
 *
 */
object PromptInjectionTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return transformMessages(
            messages = messages,
            assistant = ctx.assistant,
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            conversationModeInjectionIds = ctx.conversationModeInjectionIds,
            conversationLorebookIds = ctx.conversationLorebookIds,
        )
    }
}

/**
 */
internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<UIMessage> {
    val injections = collectInjections(
        messages = messages,
        assistant = assistant,
        modeInjections = modeInjections,
        lorebooks = lorebooks,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
    )

    if (injections.isEmpty()) {
        return messages
    }

    injections.forEach { requireSupportedInjectionRole(it.position, it.role) }

    // Priority is authoritative. UUID provides a stable secondary key so equal-priority
    // imports produce the same order regardless of collection/list reconstruction order.
    val byPosition = injections
        .sortedWith(
            compareByDescending<PromptInjection> { it.priority }
                .thenBy { it.id.toString() }
        )
        .groupBy { it.position }

    return applyInjections(messages, byPosition)
}

/**
 */
internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<PromptInjection> {
    val injections = mutableListOf<PromptInjection>()
    val effectiveModeInjectionIds = if (assistant.allowConversationPromptInjection) {
        conversationModeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val effectiveLorebookIds = if (assistant.allowConversationPromptInjection) {
        conversationLorebookIds
    } else {
        assistant.lorebookIds
    }

    modeInjections
        .filter { it.enabled && effectiveModeInjectionIds.contains(it.id) }
        .forEach { injections.add(it) }

    val enabledLorebooks = lorebooks.filter {
        it.enabled && effectiveLorebookIds.contains(it.id)
    }
    if (enabledLorebooks.isNotEmpty()) {
        val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }

        enabledLorebooks.forEach { lorebook ->
            lorebook.entries
                .filter { entry ->
                    val context = extractContextForMatching(nonSystemMessages, entry.scanDepth)
                    entry.isTriggered(context)
                }
                .forEach { injections.add(it) }
        }
    }

    return injections
}

/**
 */
internal fun applyInjections(
    messages: List<UIMessage>,
    byPosition: Map<InjectionPosition, List<PromptInjection>>
): List<UIMessage> {
    byPosition.forEach { (position, injections) ->
        injections.forEach { requireSupportedInjectionRole(position, it.role) }
    }
    val result = messages.toMutableList()

    val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }

    if (systemIndex >= 0) {
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        if (beforeContent.isNotEmpty() || afterContent.isNotEmpty()) {
            val systemMessage = result[systemIndex]
            val originalText = systemMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }

            val newText = buildString {
                if (beforeContent.isNotEmpty()) {
                    append(beforeContent)
                    appendLine()
                }
                append(originalText)
                if (afterContent.isNotEmpty()) {
                    appendLine()
                    append(afterContent)
                }
            }

            result[systemIndex] = systemMessage.copy(
                parts = listOf(UIMessagePart.Text(newText)),
                isSynthetic = true,
            )
        }
    } else {
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        val combinedContent = buildString {
            if (beforeContent.isNotEmpty()) {
                append(beforeContent)
            }
            if (afterContent.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                append(afterContent)
            }
        }

        if (combinedContent.isNotEmpty()) {
            result.add(0, UIMessage.system(combinedContent).copy(isSynthetic = true))
        }
    }

    val topInjections = byPosition[InjectionPosition.TOP_OF_CHAT]
    if (!topInjections.isNullOrEmpty()) {
        var insertIndex = result.indexOfFirst { it.role == MessageRole.USER }
            .takeIf { it >= 0 } ?: result.size
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(topInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    val bottomInjections = byPosition[InjectionPosition.BOTTOM_OF_CHAT]
    if (!bottomInjections.isNullOrEmpty()) {
        var insertIndex = (result.size - 1).coerceAtLeast(0)
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(bottomInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    val atDepthInjections = byPosition[InjectionPosition.AT_DEPTH]
    if (!atDepthInjections.isNullOrEmpty()) {
        val byDepth = atDepthInjections.groupBy { it.injectDepth }
        byDepth.keys.sortedDescending().forEach { depth ->
            val injections = byDepth[depth] ?: return@forEach
            var insertIndex = (result.size - depth.coerceAtLeast(1)).coerceIn(0, result.size)
            insertIndex = findSafeInsertIndex(result, insertIndex)
            createMergedInjectionMessages(injections).forEach { message ->
                result.add(insertIndex, message)
                insertIndex++
            }
        }
    }

    return result
}

/**
 * Merge only adjacent injections with the same role. Merging non-adjacent entries
 * would move lower-priority content across an intervening higher-priority role.
 */
private fun createMergedInjectionMessages(injections: List<PromptInjection>): List<UIMessage> {
    if (injections.isEmpty()) return emptyList()

    val consecutiveGroups = mutableListOf<MutableList<PromptInjection>>()
    injections.forEach { injection ->
        val current = consecutiveGroups.lastOrNull()
        if (current != null && current.last().role == injection.role) {
            current.add(injection)
        } else {
            consecutiveGroups.add(mutableListOf(injection))
        }
    }

    return consecutiveGroups.map { grouped ->
        val role = grouped.first().role
        val mergedContent = grouped.joinToString("\n") { it.content }
        when (role) {
            MessageRole.ASSISTANT -> UIMessage.assistant(mergedContent)
            MessageRole.USER -> UIMessage.user(mergedContent)
            else -> throw IllegalArgumentException("Unsupported standalone prompt injection role $role")
        }.copy(
            isSynthetic = true,
        )
    }
}

/**
 *
 */
internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)

    while (index > 0) {
        val prevMessage = messages.getOrNull(index - 1)
        val currentMessage = messages.getOrNull(index)

        val isPrevUser = prevMessage?.role == MessageRole.USER
        val isCurrentAssistantWithTools = currentMessage?.role == MessageRole.ASSISTANT
            && currentMessage.getTools().isNotEmpty()

        if (isPrevUser && isCurrentAssistantWithTools) {
            index--
        } else {
            break
        }
    }

    return index
}
