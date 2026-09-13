package com.example.birdingsoundmvp.owlett

import com.aallam.openai.api.exception.AuthenticationException
import com.aallam.openai.api.exception.OpenAIServerException
import com.aallam.openai.api.exception.RateLimitException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeepSeekChatEngineTest {
    @Test fun diagnosticsDoNotContainPromptsKeysOrHtml() {
        val prompt = "My private plan at hidden location"
        val raw = """{"error":{"message":"Rejected: My private plan at hidden location; sk-testsecret"}}"""
        val safe = sanitizeServiceError(raw, listOf(prompt))
        assertFalse(safe.contains(prompt))
        assertFalse(safe.contains("sk-testsecret"))
        assertFalse(sanitizeServiceError("<html>proxy debug</html>").contains("proxy debug"))
        assertFalse(OwlettErrorDisplay.from("NoTransformationFoundException: Expected response body ...").message.contains("Exception"))
    }
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `list models authenticates and excludes vision models`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"object":"list","data":[{"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek"},{"id":"deepseek-v4-flash-vision-exp","object":"model","owned_by":"deepseek"}]}"""
                )
        )
        val engine = DeepSeekChatEngine(server.url("/").toString())

        val models = engine.listModels("test-secret")

        assertEquals(listOf("deepseek-v4-pro"), models)
        val request = server.takeRequest()
        assertEquals("/models", request.path)
        assertEquals("Bearer test-secret", request.getHeader("Authorization"))
    }

    @Test
    fun `stream emits thinking state and visible text but not reasoning text`() = runBlocking {
        val body = buildString {
            append("data: {\"id\":\"chat-1\",\"created\":1,\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"private chain\"}}]}\n\n")
            append("data: {\"id\":\"chat-1\",\"created\":1,\"model\":\"deepseek-v4-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Visible answer\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )
        val engine = DeepSeekChatEngine(server.url("/").toString())

        val events = engine.streamChat(
            apiKey = "test-secret",
            request = OwlettChatRequest(
                modelId = "deepseek-v4-flash",
                messages = listOf(
                    OwlettChatRequestMessage(OwlettMessageRole.SYSTEM, "system"),
                    OwlettChatRequestMessage(OwlettMessageRole.USER, "hello")
                )
            )
        ).toList()

        assertTrue(events.first() is OwlettStreamEvent.Thinking)
        assertEquals("Visible answer", (events[1] as OwlettStreamEvent.TextDelta).text)
        assertTrue(events.last() is OwlettStreamEvent.Completed)
        assertFalse(events.filterIsInstance<OwlettStreamEvent.TextDelta>().any { it.text.contains("private chain") })
        val request = server.takeRequest()
        assertEquals("/chat/completions", request.path)
        val requestBody = request.body.readUtf8()
        assertTrue(requestBody.contains("\"max_tokens\":8192"))
        assertFalse(requestBody.contains("max_completion_tokens"))
        assertTrue(requestBody.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertFalse(requestBody.contains("tool_choice"))
    }

    @Test
    fun `streamed tool call fragments are aggregated and protocol fields are sent back`() = runBlocking {
        val body = buildString {
            append("data: {\"id\":\"chat-2\",\"created\":1,\"model\":\"deepseek-v4-pro\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"private plan\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"lookup_bird\",\"arguments\":\"{\\\"que\"}}]}}]}\n\n")
            append("data: {\"id\":\"chat-2\",\"created\":1,\"model\":\"deepseek-v4-pro\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ry\\\":\\\"owl\\\"}\"}},{\"index\":1,\"id\":\"call-2\",\"type\":\"function\",\"function\":{\"name\":\"find_bird_calls\",\"arguments\":\"{\\\"query\\\":\\\"owl\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        val runner = OwlettAgentRunner(DeepSeekChatEngine(server.url("/").toString()))
        val request = OwlettChatRequest(
            modelId = "deepseek-v4-pro",
            messages = listOf(
                OwlettChatRequestMessage(
                    role = OwlettMessageRole.ASSISTANT,
                    content = "",
                    reasoningContent = "saved reasoning",
                    toolCalls = listOf(OwlettToolCall("call-old", "lookup_bird", "{\"query\":\"crow\"}"))
                ),
                OwlettChatRequestMessage(
                    role = OwlettMessageRole.TOOL,
                    content = "{\"species\":\"crow\"}",
                    toolCallId = "call-old"
                )
            ),
            tools = listOf(
                OwlettToolDefinition(
                    "lookup_bird",
                    "Look up a bird",
                    "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}"
                )
            ),
            toolChoice = OwlettToolChoice(OwlettToolChoiceMode.NAMED, "lookup_bird"),
            thinkingEnabled = false
        )

        val step = runner.runStep("test-secret", request)

        assertEquals("private plan", step.reasoningContent)
        assertEquals(2, step.toolCalls.size)
        assertEquals("call-1", step.toolCalls[0].id)
        assertEquals("lookup_bird", step.toolCalls[0].name)
        assertEquals("{\"query\":\"owl\"}", step.toolCalls[0].argumentsJson)
        assertEquals("find_bird_calls", step.toolCalls[1].name)
        assertEquals("tool_calls", step.finishReason)
        val requestBody = server.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("\"tools\""))
        assertTrue(requestBody.contains("\"tool_choice\""))
        assertTrue(requestBody.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(requestBody.contains("\"content\":\"\""))
        assertTrue(requestBody.contains("\"reasoning_content\":\"saved reasoning\""))
        assertTrue(requestBody.contains("\"tool_call_id\":\"call-old\""))
    }

    @Test
    fun `authentication rate limit and server errors retain their typed failures`() = runBlocking {
        val errorBody = """{"error":{"message":"request failed","type":"api_error","code":"test"}}"""
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json").setBody(errorBody))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json").setBody(errorBody))
        server.enqueue(MockResponse().setResponseCode(500).setHeader("Content-Type", "application/json").setBody(errorBody))
        val engine = DeepSeekChatEngine(server.url("/").toString(), maxRetries = 0)

        assertTrue(runCatching { engine.listModels("bad-key") }.exceptionOrNull() is AuthenticationException)
        assertTrue(runCatching { engine.listModels("key") }.exceptionOrNull() is RateLimitException)
        assertTrue(runCatching { engine.listModels("key") }.exceptionOrNull() is OpenAIServerException)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `stream collection can be cancelled while the server is waiting`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val engine = DeepSeekChatEngine(server.url("/").toString(), maxRetries = 0)
        val job = launch(Dispatchers.IO) {
            engine.streamChat(
                apiKey = "test-secret",
                request = OwlettChatRequest(
                    modelId = "deepseek-v4-flash",
                    messages = listOf(OwlettChatRequestMessage(OwlettMessageRole.USER, "hello"))
                )
            ).toList()
        }

        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun `octet stream error is normalized before SDK parsing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/octet-stream")
            .setBody("{\"error\":\"forced tool requires non-thinking mode; sk-supersecret\"}"))
        val error = runCatching {
            DeepSeekChatEngine(server.url("/").toString(), 0).streamChat("test-secret", OwlettChatRequest(
                "deepseek-v4-flash", listOf(OwlettChatRequestMessage(OwlettMessageRole.USER, "hello"))
            )).toList()
        }.exceptionOrNull()
        assertTrue(error.toString(), error is DeepSeekRequestException)
        assertEquals(400, (error as DeepSeekRequestException).statusCode)
        assertFalse(error.detail.contains("sk-supersecret"))
    }
}
