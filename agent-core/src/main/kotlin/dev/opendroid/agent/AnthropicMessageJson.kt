package dev.opendroid.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Converts in-memory [ChatMessage] list to Anthropic Messages API `messages` JSON array.
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
                is ContentBlock.ToolResult -> add(
                    buildJsonObject {
                        put("type", JsonPrimitive("tool_result"))
                        put("tool_use_id", JsonPrimitive(block.toolUseId))
                        if (block.images.isEmpty()) {
                            put("content", JsonPrimitive(block.content))
                        } else {
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", JsonPrimitive("text"))
                                            put("text", JsonPrimitive(block.content))
                                        },
                                    )
                                    for (img in block.images) {
                                        add(
                                            buildJsonObject {
                                                put("type", JsonPrimitive("image"))
                                                put(
                                                    "source",
                                                    buildJsonObject {
                                                        put("type", JsonPrimitive("base64"))
                                                        put("media_type", JsonPrimitive(img.mediaType))
                                                        put("data", JsonPrimitive(img.base64Data))
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        if (block.isError) put("is_error", JsonPrimitive(true))
                    },
                )
            }
        }
    }
}
