package dev.opendroid.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Converts in-memory [ChatMessage] list to Anthropic Messages API `messages` JSON array.
 *
 * **Vision / 截图：** 官方允许 `tool_result.content` 为多段（text + image）。不少「Anthropic 兼容」网关只正确转发
 * **`content` 为字符串**的 tool_result，会丢弃或错误处理数组形态，导致模型根本收不到图。
 * 因此对有图的 [ContentBlock.ToolResult]，我们改为：
 * 1. 在本条 user 消息里**先按顺序**输出所有 `tool_result`，且 `content` **仅为**元数据字符串；
 * 2. 再在同一条消息的 `content` **末尾**按工具顺序附加所有 `image` 块（满足「先 tool_result，后其它类型」的常见约束）。
 *
 * `data` 为纯 Base64；若误带 `data:image/…;base64,` 前缀会剥离。
 */
fun List<ChatMessage>.toAnthropicMessagesArray(): JsonArray {
    val list = this
    return buildJsonArray {
        for (msg in list) {
            add(msg.toAnthropicMessageObject())
        }
    }
}

private fun ChatMessage.toAnthropicMessageObject(): JsonObject = buildJsonObject {
    put("role", JsonPrimitive(role.toApiRole()))
    when {
        blocks.size == 1 && blocks[0] is ContentBlock.Text && role == ChatRole.User ->
            put("content", JsonPrimitive((blocks[0] as ContentBlock.Text).text))
        else -> put("content", blocks.toAnthropicContentArray())
    }
}

private fun ChatRole.toApiRole(): String = when (this) {
    ChatRole.User -> "user"
    ChatRole.Assistant -> "assistant"
}

private fun anthropicImageBase64Data(raw: String): String {
    val t = raw.trim()
    val marker = ";base64,"
    val i = t.indexOf(marker, ignoreCase = true)
    if (t.startsWith("data:", ignoreCase = true) && i >= 0) {
        return t.substring(i + marker.length).trim()
    }
    return t
}

private fun anthropicImageBlock(img: ToolResultImage): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("image"))
    put(
        "source",
        buildJsonObject {
            put("type", JsonPrimitive("base64"))
            put("media_type", JsonPrimitive(img.mediaType))
            put("data", JsonPrimitive(anthropicImageBase64Data(img.base64Data)))
        },
    )
}

private fun anthropicToolResultStringContent(block: ContentBlock.ToolResult): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("tool_result"))
    put("tool_use_id", JsonPrimitive(block.toolUseId))
    put("content", JsonPrimitive(block.content))
    if (block.isError) put("is_error", JsonPrimitive(true))
}

private fun List<ContentBlock>.toAnthropicContentArray(): JsonArray {
    val blocks = this
    return buildJsonArray {
        for (block in blocks) {
            when (block) {
                is ContentBlock.Text -> add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(block.text))
                    },
                )
                is ContentBlock.ToolUse -> add(
                    buildJsonObject {
                        put("type", JsonPrimitive("tool_use"))
                        put("id", JsonPrimitive(block.id))
                        put("name", JsonPrimitive(block.name))
                        put("input", block.input)
                    },
                )
                is ContentBlock.ToolResult -> add(anthropicToolResultStringContent(block))
            }
        }
        for (block in blocks) {
            if (block is ContentBlock.ToolResult) {
                for (img in block.images) {
                    add(anthropicImageBlock(img))
                }
            }
        }
    }
}
