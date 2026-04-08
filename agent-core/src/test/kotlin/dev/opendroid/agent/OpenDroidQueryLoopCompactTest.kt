package dev.opendroid.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDroidQueryLoopCompactTest {

    @Test
    fun compactWhenPayloadOverTriggerUsesExtraLlmRound() = runBlocking {
        val conv = mutableListOf<ChatMessage>()
        repeat(8) { i ->
            conv.add(ChatMessage(ChatRole.User, listOf(ContentBlock.Text("u$i"))))
            conv.add(
                ChatMessage(
                    ChatRole.Assistant,
                    listOf(ContentBlock.Text("a$i " + "x".repeat(6_000))),
                ),
            )
        }
        val summaryTurn = AssistantTurnResult(
            blocks = listOf(ContentBlock.Text("- 摘要要点")),
            stopReason = "end_turn",
            rawTextForUi = "- 摘要要点",
        )
        val finalTurn = AssistantTurnResult(
            blocks = listOf(ContentBlock.Text("完成")),
            stopReason = "end_turn",
            rawTextForUi = "完成",
        )
        val llm = ScriptedLlmClient(listOf(summaryTurn, finalTurn))
        val loop = OpenDroidQueryLoop(
            llm = llm,
            tools = listOf(ToolDefinition("get_ui_tree", "d", JsonObject(emptyMap()))),
            toolExecutor = ToolExecutor { _, _, _ -> ToolExecutionResult("{}") },
            conversationCompactConfig = ConversationCompactConfig(
                enabled = true,
                triggerApproxPayloadChars = 8_000,
                summaryMaxTokens = 512,
            ),
        )
        loop.run(
            systemPrompt = "sys",
            conversation = conv,
            userText = "继续",
            model = "m",
            maxTurns = 6,
            emit = { },
        )
        assertTrue(
            conv.any { msg ->
                msg.blocks.any { b ->
                    b is ContentBlock.Text && b.text.contains("上文摘要")
                }
            },
        )
    }
}
