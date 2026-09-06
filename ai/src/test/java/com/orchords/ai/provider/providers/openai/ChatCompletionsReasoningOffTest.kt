package com.orchords.ai.provider.providers.openai

import com.orchords.ai.core.ReasoningLevel
import com.orchords.ai.provider.Model
import com.orchords.ai.provider.ModelAbility
import com.orchords.ai.provider.ProviderSetting
import com.orchords.ai.provider.TextGenerationParams
import com.orchords.ai.ui.UIMessage
import com.orchords.ai.util.KeyRoulette
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ChatCompletionsReasoningOffTest {
    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    @Test
    fun `generic compatible route omits reasoning effort when reasoning is off`() {
        val body = buildRequest(ReasoningLevel.OFF)
        assertFalse(body.containsKey("reasoning_effort"))
    }

    @Test
    fun `generic compatible route still emits explicit enabled effort`() {
        val body = buildRequest(ReasoningLevel.LOW)
        assertEquals("low", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    private fun buildRequest(level: ReasoningLevel): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true

        val model = Model(
            modelId = "custom-reasoning-model",
            abilities = listOf(ModelAbility.REASONING),
        )
        val params = TextGenerationParams(
            model = model,
            reasoningLevel = level,
            maxTokens = null,
        )
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://example-compatible.invalid/v1",
        )

        return method.invoke(
            api,
            listOf(UIMessage.user("hi")),
            params,
            provider,
            false,
        ) as JsonObject
    }
}
