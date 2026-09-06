package com.orchords.ai.provider

import com.orchords.ai.ui.StreamChunk
import com.orchords.ai.ui.UIMessage
import com.orchords.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Applies provider-route tool-name compatibility only to the ephemeral request/response view.
 * Canonical Tool names remain unchanged in application state, approval state, and persisted history.
 */
internal class ToolAliasingProvider<T : ProviderSetting>(
    private val delegate: Provider<T>,
) : Provider<T> by delegate {
    override suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val view = buildToolAliasRequestView(providerSetting, messages, params)
        val result = delegate.generateText(
            providerSetting = providerSetting,
            messages = view.messages,
            params = view.params,
        )
        return result.copy(
            message = reverseProviderToolAliases(result.message, view.reverseAliases),
        )
    }

    override suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val view = buildToolAliasRequestView(providerSetting, messages, params)
        return reverseProviderToolAliases(
            source = delegate.streamText(
                providerSetting = providerSetting,
                messages = view.messages,
                params = view.params,
            ),
            reverseAliases = view.reverseAliases,
        )
    }
}

internal data class ToolAliasRequestView(
    val messages: List<UIMessage>,
    val params: TextGenerationParams,
    val aliases: Map<String, String>,
    val reverseAliases: Map<String, String>,
)

internal fun buildToolAliasRequestView(
    providerSetting: ProviderSetting,
    messages: List<UIMessage>,
    params: TextGenerationParams,
): ToolAliasRequestView {
    val currentNames = params.tools.map { it.name }
    require(currentNames.none { it.isBlank() }) { "Tool names must not be blank" }
    require(currentNames.distinct().size == currentNames.size) {
        "Canonical tool names must be unique before provider serialization"
    }

    val historicalNames = messages
        .flatMap { message ->
            message.parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolName }
        }

    val aliases = buildProviderToolAliases(
        canonicalNames = currentNames + historicalNames,
        policy = resolveToolNamePolicy(providerSetting),
    )
    val reverseAliases = currentNames.associate { canonical ->
        aliases.getValue(canonical) to canonical
    }
    require(reverseAliases.size == currentNames.size) {
        "Provider tool aliases must be unique for the current request"
    }

    val requestMessages = messages.map { message ->
        val parts = message.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                part.copy(toolName = aliases.getValue(part.toolName))
            } else {
                part
            }
        }
        if (parts == message.parts) message else message.copy(parts = parts)
    }

    val requestParams = params.copy(
        tools = params.tools.map { tool ->
            tool.copy(name = aliases.getValue(tool.name))
        },
    )

    return ToolAliasRequestView(
        messages = requestMessages,
        params = requestParams,
        aliases = aliases,
        reverseAliases = reverseAliases,
    )
}

internal fun resolveToolNamePolicy(providerSetting: ProviderSetting): ToolNamePolicy =
    when (providerSetting) {
        is ProviderSetting.OpenAI -> {
            val host = providerSetting.baseUrl.toHttpUrlOrNull()?.host.orEmpty()
            when {
                host == "api.deepseek.com" && providerSetting.useResponseApi ->
                    ToolNamePolicy.DEEPSEEK_RESPONSES
                host == "api.deepseek.com" ->
                    ToolNamePolicy.DEEPSEEK_CHAT
                host == "api.openai.com" && providerSetting.useResponseApi ->
                    ToolNamePolicy.OPENAI_RESPONSES
                host == "api.openai.com" ->
                    ToolNamePolicy.OPENAI_CHAT
                else ->
                    ToolNamePolicy.OPENAI_COMPATIBLE
            }
        }

        is ProviderSetting.Google -> ToolNamePolicy.GEMINI
        is ProviderSetting.Claude -> ToolNamePolicy.CLAUDE
    }

private fun reverseProviderToolAliases(
    message: UIMessage,
    reverseAliases: Map<String, String>,
): UIMessage {
    val parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool) {
            val canonical = reverseAliases[part.toolName]
                ?: error("Provider returned unknown or stale tool alias: ${part.toolName}")
            part.copy(toolName = canonical)
        } else {
            part
        }
    }
    return if (parts == message.parts) message else message.copy(parts = parts)
}

private data class PendingToolAlias(
    val id: String,
    val providerName: StringBuilder,
    val input: StringBuilder = StringBuilder(),
    val startMetadata: JsonObject? = null,
    var deltaMetadata: JsonObject? = null,
    var ended: Boolean = false,
)

internal fun reverseProviderToolAliases(
    source: Flow<StreamChunk>,
    reverseAliases: Map<String, String>,
): Flow<StreamChunk> = flow {
    val pending = linkedMapOf<String, PendingToolAlias>()
    val deferred = mutableListOf<StreamChunk>()

    suspend fun flushCompletedPrefix() {
        while (true) {
            val first = pending.entries.firstOrNull() ?: break
            val state = first.value
            if (!state.ended) break

            val providerAlias = state.providerName.toString()
            val canonical = reverseAliases[providerAlias]
                ?: error("Provider returned unknown or stale tool alias: $providerAlias")

            emit(
                StreamChunk.ToolCallStart(
                    id = state.id,
                    toolName = canonical,
                    metadata = state.startMetadata,
                )
            )
            if (state.input.isNotEmpty() || state.deltaMetadata != null) {
                emit(
                    StreamChunk.ToolCallDelta(
                        id = state.id,
                        inputDelta = state.input.toString(),
                        metadata = state.deltaMetadata,
                    )
                )
            }
            emit(StreamChunk.ToolCallEnd(state.id))
            pending.remove(first.key)
        }

        if (pending.isEmpty() && deferred.isNotEmpty()) {
            deferred.forEach { emit(it) }
            deferred.clear()
        }
    }

    source.collect { chunk ->
        when (chunk) {
            is StreamChunk.ToolCallStart -> {
                require(chunk.id !in pending) {
                    "Provider started duplicate tool-call stream id: ${chunk.id}"
                }
                pending[chunk.id] = PendingToolAlias(
                    id = chunk.id,
                    providerName = StringBuilder(chunk.toolName),
                    startMetadata = chunk.metadata,
                )
            }

            is StreamChunk.ToolCallDelta -> {
                val state = pending[chunk.id]
                    ?: error("Provider emitted tool-call delta without start: ${chunk.id}")
                state.providerName.append(chunk.toolNameDelta)
                state.input.append(chunk.inputDelta)
                if (chunk.metadata != null) state.deltaMetadata = chunk.metadata
            }

            is StreamChunk.ToolCallEnd -> {
                val state = pending[chunk.id]
                    ?: error("Provider ended unknown tool-call stream id: ${chunk.id}")
                state.ended = true
                flushCompletedPrefix()
            }

            else -> {
                if (pending.isEmpty()) {
                    emit(chunk)
                } else {
                    deferred += chunk
                }
            }
        }
    }

    if (pending.isNotEmpty()) {
        error("Provider stream ended with incomplete tool alias state")
    }
    if (deferred.isNotEmpty()) {
        deferred.forEach { emit(it) }
    }
}
