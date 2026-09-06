package com.orchords.ai.provider.providers.openai

import com.orchords.ai.core.ReasoningLevel
import com.orchords.ai.provider.Model
import com.orchords.ai.provider.ModelAbility
import com.orchords.ai.provider.ProviderSetting
import com.orchords.ai.provider.TextGenerationParams
import com.orchords.ai.ui.UIMessage
import com.orchords.ai.util.KeyRoulette
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
    fun `strict unknown gateway also omits reasoning effort when reasoning is off`() {
        val body = buildRequest(
            level = ReasoningLevel.OFF,
            baseUrl = "https://strict-gateway.example/v1",
        )
        assertFalse(body.containsKey("reasoning_effort"))
    }

    @Test
    fun `generic compatible auto leaves reasoning effort provider default`() {
        val body = buildRequest(ReasoningLevel.AUTO)
        assertFalse(body.containsKey("reasoning_effort"))
    }

    @Test
    fun `generic compatible route still emits explicit enabled effort`() {
        val body = buildRequest(ReasoningLevel.LOW)
        assertEquals("low", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `OpenRouter uses its explicit none representation for off`() {
        val body = buildRequest(
            level = ReasoningLevel.OFF,
            baseUrl = "https://openrouter.ai/api/v1",
        )
        assertEquals("none", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `DeepSeek uses disabled thinking without enabling an effort for off`() {
        val body = buildRequest(
            level = ReasoningLevel.OFF,
            baseUrl = "https://api.deepseek.com/v1",
        )
        assertEquals("disabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning_effort"))
    }

    @Test
    fun `MiMo uses disabled thinking without generic reasoning effort for off`() {
        val body = buildRequest(
            level = ReasoningLevel.OFF,
            baseUrl = "https://api.xiaomimimo.com/v1",
        )
        assertEquals("disabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning_effort"))
    }

    private fun buildRequest(
        level: ReasoningLevel,
        baseUrl: String = "https://example-compatible.invalid/v1",
    ): JsonObject {
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
        val provider = ProviderSetting.OpenAI(baseUrl = baseUrl)

        return method.invoke(
            api,
            listOf(UIMessage.user("hi")),
            params,
            provider,
            false,
        ) as JsonObject
    }
}
