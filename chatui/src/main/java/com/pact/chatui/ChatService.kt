package com.pact.chatui

import kotlinx.coroutines.flow.Flow

/**
 * One unit of output from a streaming chat backend.
 *
 * Implementations of [ChatService] emit a sequence of these as they receive
 * tokens / chunks from the LLM, terminating with either [Done] or [Error].
 */
sealed interface ChatChunk {
    /** A delta to append to the assistant's reply. */
    data class Text(val delta: String) : ChatChunk

    /** The reply has finished normally; no further chunks will follow. */
    data object Done : ChatChunk

    /** Streaming aborted due to an error. */
    data class Error(val cause: Throwable) : ChatChunk
}

/**
 * Abstract data source that produces an assistant reply for a given chat
 * history. Implementations adapt to specific LLM providers (OpenAI,
 * Anthropic, Gemini, local models, mock data, etc.) — the chat UI in this
 * library is provider-agnostic.
 *
 * Implementations should be safe to call from any thread; the returned
 * [Flow] will be collected from the coroutine that drives streaming.
 */
fun interface ChatService {
    /**
     * Stream a reply for the given conversation. The last item in [history]
     * is typically the user message that triggered this turn; the
     * implementation decides what context to send to the underlying model.
     */
    fun streamReply(history: List<ChatMessage>): Flow<ChatChunk>
}
