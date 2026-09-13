package com.example.birdingsoundmvp.owlett

data class OwlettAgentStep(
    val content: String,
    val reasoningContent: String,
    val toolCalls: List<OwlettToolCall>,
    val modelId: String,
    val finishReason: String?
)

class OwlettAgentRunner(
    private val engine: OwlettChatEngine,
    val maxToolCallsPerTurn: Int = DEFAULT_MAX_TOOL_CALLS
) {
    suspend fun runStep(
        apiKey: String,
        request: OwlettChatRequest,
        onThinking: suspend (String) -> Unit = {},
        onTextDelta: suspend (String) -> Unit = {}
    ): OwlettAgentStep {
        var content = ""
        var reasoning = ""
        var modelId = request.modelId
        var finishReason: String? = null
        val calls = linkedMapOf<Int, MutableToolCall>()

        engine.streamChat(apiKey, request).collect { event ->
            when (event) {
                is OwlettStreamEvent.Thinking -> {
                    reasoning += event.reasoningDelta
                    onThinking(event.reasoningDelta)
                }
                is OwlettStreamEvent.TextDelta -> {
                    content += event.text
                    onTextDelta(event.text)
                }
                is OwlettStreamEvent.ToolCallDelta -> {
                    val call = calls.getOrPut(event.index) { MutableToolCall() }
                    event.id?.let { call.id = it }
                    event.name?.let { call.name = it }
                    event.argumentsDelta?.let { call.arguments.append(it) }
                }
                is OwlettStreamEvent.Completed -> {
                    modelId = event.modelId
                    finishReason = event.finishReason
                }
            }
        }
        return OwlettAgentStep(
            content = content,
            reasoningContent = reasoning,
            toolCalls = calls.entries.sortedBy { it.key }.mapIndexed { fallbackIndex, (_, call) ->
                OwlettToolCall(
                    id = call.id.ifBlank { "owlett-tool-${System.currentTimeMillis()}-$fallbackIndex" },
                    name = call.name,
                    argumentsJson = call.arguments.toString().ifBlank { "{}" }
                )
            },
            modelId = modelId,
            finishReason = finishReason
        )
    }

    private data class MutableToolCall(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    companion object {
        const val DEFAULT_MAX_TOOL_CALLS = 24
    }
}
