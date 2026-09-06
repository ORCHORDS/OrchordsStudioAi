package com.orchords.ai.provider.providers.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import com.orchords.ai.core.MessageRole
import com.orchords.ai.provider.Model
import com.orchords.ai.provider.ModelAbility
import com.orchords.ai.provider.Modality
import com.orchords.ai.provider.Provider
import com.orchords.ai.provider.ProviderSetting
import com.orchords.ai.provider.TextGenerationParams
import com.orchords.ai.provider.TextGenerationResult
import com.orchords.ai.ui.StreamChunk
import com.orchords.ai.ui.UIMessage
import com.orchords.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIChatToolResultPolicyProviderTest {
    private val imageModel = Model(
        modelId = "image-chat",
        inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        abilities = listOf(ModelAbility.TOOL),
    )
    private val textModel = imageModel.copy(
        modelId = "text-chat",
        inputModalities = listOf(Modality.TEXT),
    )

    @Test
    fun `chat lowering keeps tool output text-only and attaches image as synthetic user input`() {
        val originalTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "workspace_read_file",
            input = "{}",
            output = listOf(
                UIMessagePart.Text("image ready"),
                UIMessagePart.Image("data:image/png;base64,AAAA"),
            ),
        )
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(originalTool))

        val lowered = lowerOpenAIChatToolResultMessages(listOf(original), imageModel)

        assertEquals(2, lowered.size)
        val loweredTool = lowered[0].parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertTrue(loweredTool.output.all { it is UIMessagePart.Text })
        assertTrue(loweredTool.output.filterIsInstance<UIMessagePart.Text>()
            .any { it.text.contains("attached separately") })
        val followup = lowered[1]
        assertEquals(MessageRole.USER, followup.role)
        assertTrue(followup.isSynthetic)
        assertEquals(1, followup.parts.filterIsInstance<UIMessagePart.Image>().size)

        // Canonical history is unchanged.
        assertSame(originalTool, original.parts.single())
        assertTrue(originalTool.output.any { it is UIMessagePart.Image })
    }

    @Test
    fun `text-only model receives bounded omission without media path or base64 in text`() {
        val imageUrl = "file:///private/tool_outputs/secret.png"
        val original = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "workspace_read_file",
                    input = "{}",
                    output = listOf(UIMessagePart.Image(imageUrl)),
                )
            ),
        )

        val lowered = lowerOpenAIChatToolResultMessages(listOf(original), textModel)

        assertEquals(1, lowered.size)
        val text = lowered.single().parts.filterIsInstance<UIMessagePart.Tool>().single()
            .output.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(text.contains("does not support image input"))
        assertFalse(text.contains(imageUrl))
        assertFalse(text.contains("base64"))
    }

    @Test
    fun `parallel tool images lower to text and one deterministic follow-up media message`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool("call-a", "a", "{}", listOf(UIMessagePart.Image("data:image/png;base64,A"))),
                UIMessagePart.Tool("call-b", "b", "{}", listOf(UIMessagePart.Text("ok"), UIMessagePart.Image("data:image/png;base64,B"))),
            ),
        )

        val lowered = lowerOpenAIChatToolResultMessages(listOf(assistant), imageModel)

        assertEquals(2, lowered.size)
        assertTrue(lowered.first().parts.filterIsInstance<UIMessagePart.Tool>()
            .all { tool -> tool.output.all { it is UIMessagePart.Text } })
        assertEquals(2, lowered.last().parts.filterIsInstance<UIMessagePart.Image>().size)
    }

    @Test
    fun `responses path preserves canonical rich tool result`() = runTest {
        var received: List<UIMessage>? = null
        val delegate = object : Provider<ProviderSetting.OpenAI> {
            override suspend fun listModels(providerSetting: ProviderSetting.OpenAI) = emptyList<Model>()
            override suspend fun generateText(
                providerSetting: ProviderSetting.OpenAI,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): TextGenerationResult {
                received = messages
                return TextGenerationResult("id", params.model.modelId, UIMessage.assistant("ok"))
            }
            override suspend fun streamText(
                providerSetting: ProviderSetting.OpenAI,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): Flow<StreamChunk> = emptyFlow()
        }
        val wrapper = OpenAIChatToolResultPolicyProvider(delegate)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool("call", "tool", "{}", listOf(UIMessagePart.Image("data:image/png;base64,A")))
            ),
        )
        val setting = ProviderSetting.OpenAI(useResponseApi = true)

        wrapper.generateText(setting, listOf(message), TextGenerationParams(model = imageModel))

        assertSame(message, received!!.single())
    }
}
