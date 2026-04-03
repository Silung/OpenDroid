package dev.opendroid.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDroidQueryLoopTest {

    @Test
    fun serialToolRoundThenFinishes() = runBlocking {
        val toolTurn = AssistantTurnResult(
            blocks = listOf(
                ContentBlock.ToolUse(
                    id = "toolu_1",
                    name = "get_ui_tree",
                    input = buildJsonObject { },
                ),
            ),
            stopReason = "tool_use",
            rawTextForUi = "",
        )
        val finalTurn = AssistantTurnResult(
            blocks = listOf(ContentBlock.Text("完成")),
            stopReason = "end_turn",
            rawTextForUi = "完成",
        )
        val llm = ScriptedLlmClient(listOf(toolTurn, finalTurn))
        val toolCalls = mutableListOf<String>()
        val executor = ToolExecutor { _, name, _ ->
            toolCalls.add(name)
            ToolExecutionResult("{\"ok\":true}")
        }
        val loop = OpenDroidQueryLoop(
            llm = llm,
            tools = listOf(
                ToolDefinition(
                    name = "get_ui_tree",
                    description = "x",
                    inputSchema = JsonObject(emptyMap()),
                ),
            ),
            toolExecutor = executor,
        )
        val events = mutableListOf<QueryLoopEvent>()
        val conv = mutableListOf<ChatMessage>()
        loop.run(
            systemPrompt = "test",
            conversation = conv,
            userText = "读屏",
            model = "claude-3-5-haiku-latest",
            maxTurns = 8,
            emit = { events.add(it) },
        )
        assertEquals(listOf("get_ui_tree"), toolCalls)
        assertTrue(events.any { it is QueryLoopEvent.Finished && (it as QueryLoopEvent.Finished).reason is FinishReason.Normal })
        assertEquals(4, conv.size)
        assertEquals(ChatRole.User, conv[0].role)
        assertEquals(ChatRole.Assistant, conv[1].role)
        assertEquals(ChatRole.User, conv[2].role)
        assertEquals(ChatRole.Assistant, conv[3].role)
        val toolResults = conv[2].blocks.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals(1, toolResults.size)
        assertEquals("toolu_1", toolResults[0].toolUseId)
    }

    @Test
    fun maxTurns_stopsWhenToolsKeepComing() = runBlocking {
        val keepCalling = AssistantTurnResult(
            blocks = listOf(
                ContentBlock.ToolUse(
                    id = "t1",
                    name = "get_ui_tree",
                    input = buildJsonObject { },
                ),
            ),
            stopReason = "tool_use",
            rawTextForUi = "",
        )
        val llm = ScriptedLlmClient(List(5) { keepCalling })
        val loop = OpenDroidQueryLoop(
            llm = llm,
            tools = listOf(
                ToolDefinition("get_ui_tree", "d", JsonObject(emptyMap())),
            ),
            toolExecutor = ToolExecutor { _, _, _ -> ToolExecutionResult("{}") },
        )
        val reasons = mutableListOf<FinishReason>()
        loop.run(
            systemPrompt = "s",
            conversation = mutableListOf(),
            userText = "go",
            model = "m",
            maxTurns = 3,
            emit = { if (it is QueryLoopEvent.Finished) reasons.add(it.reason) },
        )
        assertTrue(reasons.single() is FinishReason.MaxTurns)
    }

    @Test
    fun concurrentSafeToolsInOneTurnOverlap() = runBlocking {
        val toolTurn = AssistantTurnResult(
            blocks = listOf(
                ContentBlock.ToolUse(id = "a", name = "get_ui_tree", input = buildJsonObject { }),
                ContentBlock.ToolUse(id = "b", name = "get_ui_tree", input = buildJsonObject { }),
            ),
            stopReason = "tool_use",
            rawTextForUi = "",
        )
        val finalTurn = AssistantTurnResult(
            blocks = listOf(ContentBlock.Text("done")),
            stopReason = "end_turn",
            rawTextForUi = "done",
        )
        val mutex = Mutex()
        var concurrent = 0
        var maxConcurrent = 0
        val llm = ScriptedLlmClient(listOf(toolTurn, finalTurn))
        val executor = ToolExecutor { _, _, _ ->
            mutex.withLock {
                concurrent++
                maxConcurrent = maxOf(maxConcurrent, concurrent)
            }
            delay(60)
            mutex.withLock { concurrent-- }
            ToolExecutionResult("{}")
        }
        val loop = OpenDroidQueryLoop(
            llm = llm,
            tools = listOf(
                ToolDefinition("get_ui_tree", "d", JsonObject(emptyMap())),
            ),
            toolExecutor = executor,
        )
        loop.run(
            systemPrompt = "s",
            conversation = mutableListOf(),
            userText = "go",
            model = "m",
            maxTurns = 8,
            emit = { },
        )
        assertTrue("expected parallel overlap, maxConcurrent=$maxConcurrent", maxConcurrent >= 2)
    }
}
