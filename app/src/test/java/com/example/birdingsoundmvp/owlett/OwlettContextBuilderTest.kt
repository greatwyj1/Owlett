package com.example.birdingsoundmvp.owlett

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwlettContextBuilderTest {
    @Test fun legacyHistoryIsTextOnlyForThinkingAndCurrentDateIsExplicit() {
        val result = OwlettContextBuilder.build(listOf(
            message(1, OwlettMessageRole.USER, "old question"),
            message(2, OwlettMessageRole.ASSISTANT, "old answer"),
            message(3, OwlettMessageRole.USER, "new question")
        ), now = java.time.ZonedDateTime.parse("2026-09-06T12:00:00+08:00[Asia/Shanghai]"))
        assertTrue(result.first().content.contains("2026-09-06"))
        assertTrue(result.first().content.contains("Asia/Shanghai"))
        assertTrue(result[1].content.contains("owlett_history"))
        assertTrue(result[1].content.contains("old answer"))
        assertTrue(result.drop(1).all { it.role == OwlettMessageRole.USER && it.toolCalls.isEmpty() })
    }

    @Test fun outOfOrderOrUnpairedToolMessagesCannotEnterProtocolHistory() {
        val call = OwlettChatRequestMessage(OwlettMessageRole.ASSISTANT, "",
            toolCalls = listOf(OwlettToolCall("1", "bird", "{}")))
        val receipt = OwlettChatRequestMessage(OwlettMessageRole.TOOL, "bird found", toolCallId = "1")
        assertTrue(OwlettContextBuilder.hasCompleteToolProtocol(listOf(call, receipt)))
        assertFalse(OwlettContextBuilder.hasCompleteToolProtocol(listOf(receipt, call)))
        assertFalse(OwlettContextBuilder.hasCompleteToolProtocol(listOf(call)))
        assertFalse(OwlettContextBuilder.hasCompleteToolProtocol(listOf(call, receipt, receipt)))
    }
    @Test
    fun `build keeps complete turns and appends hidden plan snapshot to newest user`() {
        val messages = listOf(
            message(1, OwlettMessageRole.USER, "What lives here?"),
            message(2, OwlettMessageRole.ASSISTANT, "Several species.", OwlettMessageStatus.COMPLETE),
            message(
                3,
                OwlettMessageRole.USER,
                "What should I look for?",
                attachmentJson = "{\"planName\":\"Wetland\"}"
            )
        )

        val result = OwlettContextBuilder.build(messages, thinkingEnabled = false)

        assertEquals(OwlettMessageRole.SYSTEM, result.first().role)
        assertEquals(4, result.size)
        assertTrue(result.last().content.startsWith("What should I look for?"))
        assertTrue(result.last().content.contains("<owlett_attachment_context"))
        assertTrue(result.last().content.contains("Wetland"))
    }

    @Test
    fun `build excludes failed turns and trims only whole old turns`() {
        val failed = listOf(
            message(1, OwlettMessageRole.USER, "failed-user"),
            message(2, OwlettMessageRole.ASSISTANT, "partial", OwlettMessageStatus.ERROR),
            message(3, OwlettMessageRole.USER, "old-user"),
            message(4, OwlettMessageRole.ASSISTANT, "old-answer", OwlettMessageStatus.COMPLETE),
            message(5, OwlettMessageRole.USER, "new-user")
        )
        val limit = OwlettContextBuilder.SYSTEM_PROMPT.length + "new-user".length +
            "old-user".length + "old-answer".length - 1

        val result = OwlettContextBuilder.build(failed, characterLimit = limit)

        assertEquals(listOf(OwlettMessageRole.SYSTEM, OwlettMessageRole.USER), result.map { it.role })
        assertEquals("new-user", result.last().content)
        assertFalse(result.any { it.content.contains("failed-user") || it.content.contains("partial") })
    }

    @Test
    fun `build preserves reasoning tool calls and matching tool results`() {
        val messages = listOf(
            message(1, OwlettMessageRole.USER, "Find an owl"),
            message(
                2,
                OwlettMessageRole.ASSISTANT,
                "",
                reasoningContent = "private reasoning",
                toolCallsJson = """[{"id":"call-1","name":"lookup_bird","argumentsJson":"{\"query\":\"owl\"}"}]"""
            ),
            message(
                3,
                OwlettMessageRole.TOOL,
                "{\"species\":\"owl\"}",
                toolCallId = "call-1"
            ),
            message(4, OwlettMessageRole.ASSISTANT, "Here is the owl.", reasoningContent = "final reasoning")
        )

        val result = OwlettContextBuilder.build(messages)

        assertEquals(
            listOf(
                OwlettMessageRole.SYSTEM,
                OwlettMessageRole.USER,
                OwlettMessageRole.ASSISTANT,
                OwlettMessageRole.TOOL,
                OwlettMessageRole.ASSISTANT
            ),
            result.map { it.role }
        )
        assertEquals("private reasoning", result[2].reasoningContent)
        assertEquals("lookup_bird", result[2].toolCalls.single().name)
        assertEquals("call-1", result[3].toolCallId)
    }

    private fun message(
        id: Long,
        role: OwlettMessageRole,
        content: String,
        status: OwlettMessageStatus = OwlettMessageStatus.COMPLETE,
        attachmentJson: String? = null,
        reasoningContent: String? = null,
        toolCallsJson: String? = null,
        toolCallId: String? = null
    ) = OwlettMessage(
        id = id,
        conversationId = 1,
        role = role,
        content = content,
        attachmentJson = attachmentJson,
        attachmentLabel = null,
        modelId = null,
        status = status,
        errorMessage = null,
        createdAtMs = id,
        updatedAtMs = id,
        reasoningContent = reasoningContent,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId
    )
}
