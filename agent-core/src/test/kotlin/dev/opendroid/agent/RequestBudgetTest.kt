package dev.opendroid.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestBudgetTest {

    @Test
    fun toolResultTruncatesWhenOverCap() {
        val raw = "a".repeat(5_000)
        val msg = ChatMessage(
            ChatRole.User,
            listOf(ContentBlock.ToolResult(toolUseId = "t1", content = raw)),
        )
        val out = listOf(msg).applyToolResultBudget(ToolResultBudgetPolicy(maxTextCharsPerToolResult = 800))
        val tr = out.single().blocks.single() as ContentBlock.ToolResult
        assertTrue(tr.content.length < raw.length)
        assertTrue(tr.content.contains("OpenDroid：tool_result"))
    }

    @Test
    fun textBlockTruncatesWhenOverCap() {
        val raw = "z".repeat(10_000)
        val msg = ChatMessage(ChatRole.Assistant, listOf(ContentBlock.Text(raw)))
        val out = listOf(msg).applyToolResultBudget(
            ToolResultBudgetPolicy(maxTextCharsPerToolResult = 12_000, maxCharsPerTextBlock = 900),
        )
        val text = out.single().blocks.single() as ContentBlock.Text
        assertTrue(text.text.length < raw.length)
        assertTrue(text.text.contains("OpenDroid：text"))
    }

    @Test
    fun estimatorCountsToolSchemasAndImages() {
        val tools = listOf(
            ToolDefinition(
                name = "n",
                description = "d",
                inputSchema = buildJsonObject { },
            ),
        )
        val img = ToolResultImage(mediaType = "image/jpeg", base64Data = "abcd".repeat(100))
        val messages = listOf(
            ChatMessage(
                ChatRole.User,
                listOf(
                    ContentBlock.ToolResult(
                        toolUseId = "x",
                        content = "hi",
                        images = listOf(img),
                    ),
                ),
            ),
        )
        val est = RequestPayloadEstimator.approximatePayloadChars("system", messages, tools)
        assertTrue(est > "system".length + img.base64Data.length)
    }

    @Test
    fun compactionTriggerEstimatorIgnoresBulkImageBase64() {
        val tools = emptyList<ToolDefinition>()
        val huge = "x".repeat(200_000)
        val img = ToolResultImage(mediaType = "image/jpeg", base64Data = huge)
        val messages = listOf(
            ChatMessage(
                ChatRole.User,
                listOf(
                    ContentBlock.ToolResult(toolUseId = "id", content = "{}", images = listOf(img)),
                ),
            ),
        )
        val full = RequestPayloadEstimator.approximatePayloadChars("", messages, tools)
        val forCompact = RequestPayloadEstimator.approximateCompactionTriggerPayloadChars("", messages, tools)
        assertTrue(full > 150_000)
        assertTrue(forCompact < 10_000)
    }

    @Test
    fun digestIncludesToolNames() {
        val list = listOf(
            ChatMessage(
                ChatRole.Assistant,
                listOf(
                    ContentBlock.ToolUse(id = "id1", name = "get_ui_tree", input = JsonObject(emptyMap())),
                ),
            ),
        )
        val d = list.toCompactDigestText(maxTotalChars = 10_000)
        assertTrue(d.contains("get_ui_tree"))
        assertTrue(d.contains("id1"))
    }
}
