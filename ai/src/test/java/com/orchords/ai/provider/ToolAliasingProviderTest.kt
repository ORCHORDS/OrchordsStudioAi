package com.orchords.ai.provider

import com.orchords.ai.core.MessageRole
import com.orchords.ai.core.Tool
import com.orchords.ai.ui.StreamChunk
import com.orchords.ai.ui.UIMessage
import com.orchords.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAliasingProviderTest {
    @Test(timeout = 5_000)
    fun `unsafe request names are aliased and non-stream result names return canonical`() {
        val canonical = "mcp__My MCP__文件.read/" + "segment".repeat(20)
        val tool = tool(canonical)
        val history = executedToolMessage(canonical)
        val delegate = RecordingOpenAIProvider()
        val provider = ToolAliasingProvider(delegate)

        val result = runBlocking {
            provider.generateText(
                providerSetting = openAi("https://example.com/v1"),
                messages = listOf(history),
                params = params(tool),
            )
        }

        val wireName = delegate.lastParams.tools.single().name
        assertTrue(wireName.length <= 64)
        assertTrue(wireName.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(canonical, wireName)
        assertEquals(wireName, delegate.lastMessages.single().getTools().single().toolName)
        assertEquals(canonical, result.message.getTools().single().toolName)
        assertEquals(canonical, history.getTools().single().toolName)
        assertEquals(canonical, tool.name)
    }

    @Test(timeout = 5_000)
    fun `unknown provider alias fails closed without executing the local tool`() {
        var executions = 0
        val canonical = "mcp__server__dangerous.tool"
        val tool = Tool(
            name = canonical,
            description = "",
            execute = {
                executions++
                emptyList()
            },
        )
        val delegate = RecordingOpenAIProvider(
            resultName = { "stale_or_forged_alias" },
        )
        val provider = ToolAliasingProvider(delegate)

        var failed = false
        try {
            runBlocking {
                provider.generateText(
                    providerSetting = openAi("https://example.com/v1"),
                    messages = emptyList(),
                    params = params(tool),
                )
            }
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(1, delegate.generateCalls)
        assertEquals(0, executions)
    }

    @Test(timeout = 5_000)
    fun `fragmented streaming alias is buffered and restored before consumers see it`() {
        val canonical = "mcp__My MCP__files.read"
        val delegate = RecordingOpenAIProvider(
            streamFactory = { requestParams ->
                flow {
                    val alias = requestParams.tools.single().name
                    val split = (alias.length / 2).coerceAtLeast(1)
                    emit(StreamChunk.ToolCallStart(id = "call-1", toolName = alias.take(split)))
                    emit(
                        StreamChunk.ToolCallDelta(
                            id = "call-1",
                            toolNameDelta = alias.drop(split),
                            inputDelta = """{"path":"a.txt"}""",
                        )
                    )
                    emit(StreamChunk.ToolCallEnd(id = "call-1"))
                    emit(StreamChunk.Finish(finishReason = "tool_calls"))
                }
            },
        )
        val provider = ToolAliasingProvider(delegate)

        val chunks = runBlocking {
            provider.streamText(
                providerSetting = openAi("https://api.deepseek.com"),
                messages = emptyList(),
                params = params(tool(canonical)),
            ).toList()
        }

        assertEquals(
            listOf(
                StreamChunk.ToolCallStart(id = "call-1", toolName = canonical),
                StreamChunk.ToolCallDelta(
                    id = "call-1",
                    inputDelta = """{"path":"a.txt"}""",
                ),
                StreamChunk.ToolCallEnd(id = "call-1"),
                StreamChunk.Finish(finishReason = "tool_calls"),
            ),
            chunks,
        )
    }

    @Test(timeout = 5_000)
    fun `parallel fragmented tool calls preserve start order and canonical identity`() {
        val first = "mcp__Server One__files.read"
        val second = "mcp__Server Two__files.read"
        val delegate = RecordingOpenAIProvider(
            streamFactory = { requestParams ->
                flow {
                    val aliases = requestParams.tools.map { it.name }
                    val a = aliases[0]
                    val b = aliases[1]
                    val splitA = (a.length / 2).coerceAtLeast(1)
                    val splitB = (b.length / 2).coerceAtLeast(1)

                    emit(StreamChunk.ToolCallStart("a", a.take(splitA)))
                    emit(StreamChunk.ToolCallStart("b", b.take(splitB)))
                    emit(StreamChunk.ToolCallDelta("b", b.drop(splitB), """{"b":2}"""))
                    emit(StreamChunk.ToolCallEnd("b"))
                    emit(StreamChunk.ToolCallDelta("a", a.drop(splitA), """{"a":1}"""))
                    emit(StreamChunk.ToolCallEnd("a"))
                }
            },
        )
        val provider = ToolAliasingProvider(delegate)

        val chunks = runBlocking {
            provider.streamText(
                providerSetting = openAi("https://example.com/v1"),
                messages = emptyList(),
                params = params(tool(first), tool(second)),
            ).toList()
        }

        assertEquals(first, (chunks[0] as StreamChunk.ToolCallStart).toolName)
        assertEquals("""{"a":1}""", (chunks[1] as StreamChunk.ToolCallDelta).inputDelta)
        assertEquals(second, (chunks[3] as StreamChunk.ToolCallStart).toolName)
        assertEquals("""{"b":2}""", (chunks[4] as StreamChunk.ToolCallDelta).inputDelta)
    }

    @Test
    fun `provider switches rebuild aliases from canonical history using route policy`() {
        val canonical = "mcp__" + "long.server.name/".repeat(8) + "read"
        val history = executedToolMessage(canonical)
        val request = params(tool(canonical))

        val deepSeekChat = buildToolAliasRequestView(
            openAi("https://api.deepseek.com", responses = false),
            listOf(history),
            request,
        )
        val deepSeekResponses = buildToolAliasRequestView(
            openAi("https://api.deepseek.com", responses = true),
            listOf(history),
            request,
        )
        val gemini = buildToolAliasRequestView(
            ProviderSetting.Google(),
            listOf(history),
            request,
        )

        val chatAlias = deepSeekChat.params.tools.single().name
        val responsesAlias = deepSeekResponses.params.tools.single().name
        val geminiAlias = gemini.params.tools.single().name

        assertTrue(chatAlias.length <= 64)
        assertTrue(responsesAlias.length <= 128)
        assertTrue(geminiAlias.length <= 128)
        assertNotEquals(chatAlias, responsesAlias)
        assertEquals(canonical, history.getTools().single().toolName)
    }

    @Test
    fun `duplicate canonical names fail before provider invocation`() {
        val duplicate = "same_tool"
        val delegate = RecordingOpenAIProvider()
        val provider = ToolAliasingProvider(delegate)

        var failed = false
        try {
            runBlocking {
                provider.generateText(
                    providerSetting = openAi("https://example.com/v1"),
                    messages = emptyList(),
                    params = params(tool(duplicate), tool(duplicate)),
                )
            }
        } catch (_: IllegalArgumentException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(0, delegate.generateCalls)
    }

    @Test
    fun `route resolver distinguishes current provider contracts`() {
        assertEquals(
            ToolNamePolicy.DEEPSEEK_CHAT,
            resolveToolNamePolicy(openAi("https://api.deepseek.com", responses = false)),
        )
        assertEquals(
            ToolNamePolicy.DEEPSEEK_RESPONSES,
            resolveToolNamePolicy(openAi("https://api.deepseek.com", responses = true)),
        )
        assertEquals(ToolNamePolicy.GEMINI, resolveToolNamePolicy(ProviderSetting.Google()))
        assertEquals(ToolNamePolicy.CLAUDE, resolveToolNamePolicy(ProviderSetting.Claude()))
        assertEquals(
            ToolNamePolicy.OPENAI_COMPATIBLE,
            resolveToolNamePolicy(openAi("https://gateway.example/v1")),
        )
    }

    private fun params(vararg tools: Tool): TextGenerationParams = TextGenerationParams(
        model = Model(modelId = "test-model"),
        tools = tools.toList(),
    )

    private fun tool(name: String): Tool = Tool(
        name = name,
        description = "",
        execute = { emptyList() },
    )

    private fun executedToolMessage(name: String): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "history-call",
                toolName = name,
                input = "{}",
                output = listOf(UIMessagePart.Text("ok")),
            )
        ),
    )

    private fun openAi(
        baseUrl: String,
        responses: Boolean = false,
    ) = ProviderSetting.OpenAI(
        baseUrl = baseUrl,
        useResponseApi = responses,
    )

    private class RecordingOpenAIProvider(
        private val resultName: (TextGenerationParams) -> String = { it.tools.single().name },
        private val streamFactory: ((TextGenerationParams) -> Flow<StreamChunk>)? = null,
    ) : Provider<ProviderSetting.OpenAI> {
        var generateCalls: Int = 0
        lateinit var lastParams: TextGenerationParams
        var lastMessages: List<UIMessage> = emptyList()

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            generateCalls++
            lastMessages = messages
            lastParams = params
            return TextGenerationResult(
                id = "response",
                model = params.model.modelId,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call-1",
                            toolName = resultName(params),
                            input = "{}",
                        )
                    ),
                ),
                finishReason = "tool_calls",
            )
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            lastMessages = messages
            lastParams = params
            return streamFactory?.invoke(params) ?: flow {
                emit(StreamChunk.Finish(finishReason = "stop"))
            }
        }
    }
}
