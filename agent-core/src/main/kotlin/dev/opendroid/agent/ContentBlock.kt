package dev.opendroid.agent

import kotlinx.serialization.json.JsonObject

/** 随 [ContentBlock.ToolResult] 发往模型的截图等二进制（Anthropic tool_result 多段 content）。 */
data class ToolResultImage(
    val mediaType: String,
    val base64Data: String,
)

sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlock()
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
        val images: List<ToolResultImage> = emptyList(),
    ) : ContentBlock()
}

enum class ChatRole {
    User,
    Assistant,
}

data class ChatMessage(
    val role: ChatRole,
    val blocks: List<ContentBlock>,
)
