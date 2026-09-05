package com.orchords.orchordsai.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.orchords.ai.core.MessageRole
import com.orchords.ai.provider.CustomBody
import com.orchords.ai.provider.CustomHeader
import com.orchords.ai.ui.UIMessage
import com.orchords.ai.core.ReasoningLevel
import com.orchords.orchordsai.data.ai.tools.local.LocalToolOption
import com.orchords.orchordsai.utils.SimpleCache
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null,
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false,
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false,
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val enableWebSearch: Boolean = false,
    val workspaceId: Uuid? = null,
    val background: String? = null,
    val backgroundOpacity: Float = 1.0f,
    val useGradientBackground: Boolean = false,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    val enabledSkills: Set<String> = emptySet(),
    val enableTimeReminder: Boolean = false,
    val allowConversationSystemPrompt: Boolean = false,
    val allowConversationPromptInjection: Boolean = false,
)

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
)

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "",
    val replaceString: String = "",
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false,
)

private val regexCache = SimpleCache.builder<String, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

private fun compileRegexCached(pattern: String): Regex? {
    regexCache.getIfPresent(pattern)?.let { return it.getOrNull() }
    val result = runCatching { Regex(pattern) }.onFailure { it.printStackTrace() }
    regexCache.put(pattern, result)
    return result.getOrNull()
}

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            val compiled = compileRegexCached(regex.findRegex) ?: return@fold acc
            try {
                acc.replace(
                    regex = compiled,
                    replacement = regex.replaceString,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                acc
            }
        } else {
            acc
        }
    }
}

/**
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,

    @SerialName("top_of_chat")
    TOP_OF_CHAT,

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,

    @SerialName("at_depth")
    AT_DEPTH,
}

/**
 *
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int
    abstract val role: MessageRole

    /**
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()

    /**
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),
        val useRegex: Boolean = false,
        val caseSensitive: Boolean = false,
        val scanDepth: Int = 4,
        val constantActive: Boolean = false,
    ) : PromptInjection()
}

/**
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)

/**
 *
 */
fun PromptInjection.RegexInjection.isTriggered(context: String): Boolean {
    if (!enabled) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false

    return keywords.any { keyword ->
        // Empty imported/editor rows must not turn into implicit constant-active entries.
        if (keyword.isBlank()) return@any false
        if (useRegex) {
            try {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(keyword, options).containsMatchIn(context)
            } catch (e: Exception) {
                false
            }
        } else {
            if (caseSensitive) {
                context.contains(keyword)
            } else {
                context.contains(keyword, ignoreCase = true)
            }
        }
    }
}

/**
 *
 */
/** Product limit for lorebook trigger history; default per-entry depth remains four. */
const val MAX_LOREBOOK_SCAN_DEPTH = 256

fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int
): String {
    return messages
        // Only actual conversational turns count toward trigger depth. Generated context
        // must not activate other books or consume the configured history window.
        .asReversed().asSequence()
        .filterNot { it.isSynthetic || it.role == MessageRole.SYSTEM }
        .take(scanDepth.coerceIn(0, MAX_LOREBOOK_SCAN_DEPTH))
        .toList().asReversed()
        .joinToString("\n") { it.toText() }
}

/**
 *
 */
fun getTriggeredInjections(
    injections: List<PromptInjection.RegexInjection>,
    context: String
): List<PromptInjection.RegexInjection> {
    return injections
        .filter { it.isTriggered(context) }
        .sortedByDescending { it.priority }
}
