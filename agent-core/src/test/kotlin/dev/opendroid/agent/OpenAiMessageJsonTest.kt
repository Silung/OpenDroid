package dev.opendroid.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiMessageJsonTest {

    @Test
    fun openAiChatCompletionsEndpoint_addsV1() {
        assertTrue(
            openAiChatCompletionsEndpoint("https://api.siliconflow.cn")
                .endsWith("/v1/chat/completions"),
        )
        assertTrue(
            openAiChatCompletionsEndpoint("https://api.siliconflow.cn/v1/")
                .endsWith("/v1/chat/completions"),
        )
    }

    @Test
    fun expandUserMessage_toolsThenVisionUser() {
        val img = ToolResultImage("image/jpeg", "abc")
        val msg = ChatMessage(
            ChatRole.User,
            listOf(
                ContentBlock.ToolResult("call_1", """{"ok":true}""", false, listOf(img)),
            ),
        )
        val pieces = openAiExpandUserMessage(msg)
        val raw = Json.encodeToString(
            JsonArray.serializer(),
            buildJsonArray { pieces.forEach { add(it) } },
        )
        val iTool = raw.indexOf("\"role\":\"tool\"")
        val iUserImg = raw.indexOf("\"type\":\"image_url\"")
        assertTrue(iTool in 0 until iUserImg)
        assertTrue(raw.contains("data:image/jpeg;base64,abc"))
    }
}
