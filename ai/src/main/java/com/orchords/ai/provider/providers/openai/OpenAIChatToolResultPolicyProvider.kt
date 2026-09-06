package com.orchords.ai.provider.providers.openai

import kotlinx.coroutines.flow.Flow
import com.orchords.ai.core.MessageRole
import com.orchords.ai.provider.Modality
import com.orchords.ai.provider.Model
import com.orchords.ai.provider.Provider
import com.orchords.ai.provider.ProviderSetting
import com.orchords.ai.provider.TextGenerationParams
import com.orchords.ai.provider.TextGenerationResult
import com.orchords.ai.ui.StreamChunk
import com.orchords.ai.ui.UIMessage
import com.orchords.ai.ui.UIMessagePart

/**
 * OpenAI-compatible Chat Completions accepts text tool-result content. Rich media is lowered
 * into an ephemeral synthetic user input after the canonical tool result when the model can
 * accept that modality. Stored conversation history is never mutated by this adapter.
 */
internal class OpenAIChatToolResultPolicyProvider(
    private val delegate: Provider<ProviderSetting.OpenAI>,
) : Provider<ProviderSetting.OpenAI> by delegate {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = delegate.generateText(
        providerSetting = providerSetting,
        messages = requestMessages(providerSetting, messages, params.model),
        params = params,
    )

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = delegate.streamText(
        providerSetting = providerSetting,
        messages = requestMessages(providerSetting, messages, params.model),
        params = params,
    )

    private fun requestMessages(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        model: Model,
    ): List<UIMessage> = if (providerSetting.useResponseApi) {
        messages
    } else {
        lowerOpenAIChatToolResultMessages(messages, model)
    }
}

internal fun lowerOpenAIChatToolResultMessages(
    messages: List<UIMessage>,
    model: Model,
): List<UIMessage> = buildList {
    val supportsImageInput = Modality.IMAGE in model.inputModalities
    messages.forEach { message ->
        if (message.role != MessageRole.ASSISTANT) {
            add(message)
            return@forEach
        }

        val images = mutableListOf<UIMessagePart.Image>()
        var changed = false
        val loweredParts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || part.output.isEmpty()) return@map part

            val loweredOutput = buildList<UIMessagePart> {
                part.output.forEach { output ->
                    when (output) {
                        is UIMessagePart.Text -> add(output)
                        is UIMessagePart.Image -> {
                            changed = true
                            if (supportsImageInput) {
                                images += output
                                add(UIMessagePart.Text("[Image result attached separately for protocol compatibility]"))
                            } else {
                                add(UIMessagePart.Text("[Image output omitted: current model does not support image input]"))
                            }
                        }
                        is UIMessagePart.Document -> {
                            changed = true
                            add(UIMessagePart.Text("[Document tool output omitted from Chat Completions tool content]"))
                        }
                        is UIMessagePart.Audio -> {
                            changed = true
                            add(UIMessagePart.Text("[Audio tool output omitted from Chat Completions tool content]"))
                        }
                        is UIMessagePart.Video -> {
                            changed = true
                            add(UIMessagePart.Text("[Video tool output omitted from Chat Completions tool content]"))
                        }
                        else -> {
                            changed = true
                            add(UIMessagePart.Text("[Non-text tool output omitted from Chat Completions tool content]"))
                        }
                    }
                }
            }
            part.copy(output = loweredOutput)
        }

        add(if (changed) message.copy(parts = loweredParts) else message)
        if (images.isNotEmpty()) {
            add(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("Attached image(s) from tool result:")) + images,
                    isSynthetic = true,
                )
            )
        }
    }
}
