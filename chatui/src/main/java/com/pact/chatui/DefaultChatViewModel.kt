package com.pact.chatui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drop-in [ViewModel] that hosts the chat state and drives streaming through
 * a [ChatService]. Tracks `messages`, `draft`, and `isStreaming` as Compose
 * state, ready to plug into [ChatScreen].
 *
 * Apps that need custom plumbing (different message id scheme, branching,
 * tool calls, etc.) can ignore this and roll their own — the chat UI only
 * needs the three state fields and a `send` callback.
 */
open class DefaultChatViewModel(
    private val service: ChatService,
    /** Delay between user send and assistant transitioning to Streaming. */
    private val thinkingDelayMs: Long = 1200L,
) : ViewModel() {

    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages

    var draft by mutableStateOf("")
        private set

    var isStreaming by mutableStateOf(false)
        private set

    fun onDraftChange(value: String) {
        draft = value
    }

    fun send(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || isStreaming) return

        _messages += ChatMessage(
            id = "user-${_messages.size}",
            sender = MessageSender.User,
            text = trimmed,
        )
        draft = ""

        val assistantIndex = _messages.size
        _messages += ChatMessage(
            id = "assistant-$assistantIndex",
            sender = MessageSender.Assistant,
            text = "",
            streamState = StreamState.Thinking,
        )
        isStreaming = true

        viewModelScope.launch {
            delay(thinkingDelayMs)
            _messages[assistantIndex] = _messages[assistantIndex].copy(
                streamState = StreamState.Streaming,
            )

            val builder = StringBuilder()
            service.streamReply(_messages.toList()).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Text -> {
                        builder.append(chunk.delta)
                        _messages[assistantIndex] = _messages[assistantIndex].copy(
                            text = builder.toString(),
                        )
                    }
                    ChatChunk.Done -> Unit
                    is ChatChunk.Error -> Unit
                }
            }

            _messages[assistantIndex] = _messages[assistantIndex].copy(
                streamState = StreamState.Complete,
            )
            isStreaming = false
        }
    }
}
