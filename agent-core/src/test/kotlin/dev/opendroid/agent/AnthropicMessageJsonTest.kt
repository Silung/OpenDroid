package dev.opendroid.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMessageJsonTest {

    private val json = Json { prettyPrint = false }

    @Test
    fun toolResult_withImage_emitsStringToolResultsThenTopLevelImages() {
        val msg = ChatMessage(
            ChatRole.User,
            listOf(
                ContentBlock.ToolResult(
                    toolUseId = "tu_tree",
                    content = """{"tree":1}""",
                ),
                ContentBlock.ToolResult(
                    toolUseId = "tu_cap",
                    content = """{"ok":true}""",
                    images = listOf(ToolResultImage("image/jpeg", "qqq")),
                ),
            ),
        )
        val raw = json.encodeToString(JsonArray.serializer(), listOf(msg).toAnthropicMessagesArray())
        assertTrue(raw.indexOf("\"type\":\"tool_result\"") < raw.indexOf("\"type\":\"image\""))
        val lastTool = raw.lastIndexOf("\"type\":\"tool_result\"")
        val firstImg = raw.indexOf("\"type\":\"image\"")
        assertTrue(lastTool < firstImg)
        assertTrue(raw.contains("\"content\":\"{\\\"ok\\\":true}\""))
        assertTrue(raw.contains("\"data\":\"qqq\""))
        assertFalse(raw.contains("\"type\":\"url\""))
    }

    @Test
    fun toolResult_stripsDataUrlPrefixFromBase64() {
        val msg = ChatMessage(
            ChatRole.User,
            listOf(
                ContentBlock.ToolResult(
                    toolUseId = "t1",
                    content = "meta",
                    images = listOf(
                        ToolResultImage("image/jpeg", "data:image/jpeg;base64,QUJDRA=="),
                    ),
                ),
            ),
        )
        val raw = json.encodeToString(JsonArray.serializer(), listOf(msg).toAnthropicMessagesArray())
        assertTrue(raw.contains("\"data\":\"QUJDRA==\""))
        assertFalse(raw.contains("data:image"))
    }
}
