package com.example.birdingsoundmvp.owlett

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.FunctionCall
import com.aallam.openai.api.chat.Tool
import com.aallam.openai.api.chat.ToolCall
import com.aallam.openai.api.chat.ToolChoice as ApiToolChoice
import com.aallam.openai.api.chat.ToolId
import com.aallam.openai.api.core.Parameters
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import io.ktor.client.engine.okhttp.OkHttpConfig

interface OwlettChatEngine {
    fun streamChat(apiKey: String, request: OwlettChatRequest): Flow<OwlettStreamEvent>
    suspend fun listModels(apiKey: String): List<String>
}

class DeepSeekChatEngine(
    private val baseUrl: String = DEEPSEEK_BASE_URL,
    private val maxRetries: Int = 2
) : OwlettChatEngine {
    override fun streamChat(apiKey: String, request: OwlettChatRequest): Flow<OwlettStreamEvent> = flow {
        val compatibility = DeepSeekCompatibility(request.thinkingEnabled)
        val client = client(apiKey, compatibility)
        var resolvedModel = request.modelId
        var finishReason: String? = null
        try {
            @Suppress("DEPRECATION")
            val completionRequest = ChatCompletionRequest(
                model = ModelId(request.modelId),
                messages = request.messages.map { message ->
                    ChatMessage(
                        role = when (message.role) {
                            OwlettMessageRole.SYSTEM -> ChatRole.System
                            OwlettMessageRole.USER -> ChatRole.User
                            OwlettMessageRole.ASSISTANT -> ChatRole.Assistant
                            OwlettMessageRole.TOOL -> ChatRole.Tool
                        },
                        content = message.content,
                        toolCalls = message.toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
                            ToolCall.Function(
                                id = ToolId(call.id),
                                function = FunctionCall(call.name, call.argumentsJson)
                            )
                        },
                        toolCallId = message.toolCallId?.let(::ToolId),
                        reasoningContent = message.reasoningContent
                    )
                },
                maxTokens = request.maxCompletionTokens,
                tools = request.tools.takeIf { it.isNotEmpty() }?.map { definition ->
                    Tool.function(
                        name = definition.name,
                        description = definition.description,
                        parameters = Parameters.fromJsonString(definition.parametersJsonSchema)
                    )
                },
                toolChoice = if (request.thinkingEnabled) null else when (request.toolChoice.mode) {
                    OwlettToolChoiceMode.NONE -> ApiToolChoice.None
                    OwlettToolChoiceMode.AUTO -> ApiToolChoice.Auto
                    OwlettToolChoiceMode.REQUIRED -> ApiToolChoice.Required
                    OwlettToolChoiceMode.NAMED -> request.toolChoice.toolName
                        ?.let(ApiToolChoice::function)
                        ?: ApiToolChoice.Required
                }
            )
            client.chatCompletions(completionRequest).collect { chunk ->
                resolvedModel = chunk.model.id
                chunk.choices.forEach { choice ->
                    finishReason = choice.finishReason?.value ?: finishReason
                    val delta = choice.delta
                    delta?.reasoningContent?.takeIf(String::isNotEmpty)?.let {
                        emit(OwlettStreamEvent.Thinking(it))
                    }
                    delta?.content?.takeIf(String::isNotEmpty)?.let { emit(OwlettStreamEvent.TextDelta(it)) }
                    delta?.toolCalls.orEmpty().forEach { call ->
                        emit(
                            OwlettStreamEvent.ToolCallDelta(
                                index = call.index,
                                id = call.id?.id,
                                name = call.function?.nameOrNull,
                                argumentsDelta = call.function?.argumentsOrNull
                            )
                        )
                    }
                }
            }
            emit(OwlettStreamEvent.Completed(resolvedModel, finishReason))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val serviceError = generateSequence(error) { it.cause }.filterIsInstance<DeepSeekRequestException>().firstOrNull()
            throw serviceError ?: compatibility.lastFailure ?: error
        } finally {
            client.close()
        }
    }

    override suspend fun listModels(apiKey: String): List<String> {
        val client = client(apiKey)
        return try {
            client.models()
                .map { it.id.id }
                .filterNot { it.contains("vision", ignoreCase = true) }
                .distinct()
                .sorted()
        } finally {
            client.close()
        }
    }

    private fun client(apiKey: String, compatibility: DeepSeekCompatibility? = null) = OpenAI(
        token = apiKey,
        logging = LoggingConfig(logLevel = LogLevel.None),
        timeout = Timeout(request = 5.minutes, connect = 20.seconds, socket = 90.seconds),
        host = OpenAIHost(baseUrl = baseUrl.ensureTrailingSlash()),
        retry = RetryStrategy(maxRetries = maxRetries),
        httpClientConfig = {
            engine {
                if (compatibility != null) (this as OkHttpConfig).addInterceptor(compatibility)
            }
        }
    )

    private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"

    companion object {
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/"
    }
}
