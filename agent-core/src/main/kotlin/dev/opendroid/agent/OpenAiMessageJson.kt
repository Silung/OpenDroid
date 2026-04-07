package dev.opendroid.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SiliconFlow / OpenAI 兼容 **Chat Completions**：`messages` 与多模态 `image_url`。
 *
 * @see OpenAiChatCompletionsLlmClient
 */
fun openAiChatCompletionsEndpoint(baseUrlRaw: String): String {
    val trimmed = baseUrlRaw.trim().trimEnd('/')
    val base = if (trimmed.endsWith("/v1", ignoreCase = true)) trimmed else "$trimmed/v1"
    return "$base/chat/completions"
}

fun buildOpenAiChatCompletionRequestBody(request: LlmRequest, jsonFmt: Json): JsonObject = buildJsonObject {
    put("model", JsonPrimitive(request.model))
    put("max_tokens", JsonPrimitive(request.maxTokens))
    put("stream", JsonPrimitive(true))
    put("messages", chatMessagesToOpenAiArray(request.systemPrompt, request.messages, jsonFmt))
    if (request.tools.isNotEmpty()) {
        put("tools", toolDefinitionsToOpenAi(request.tools))
    }
}

private fun toolDefinitionsToOpenAi(tools: List<ToolDefinition>): JsonArray = buildJsonArray {
    for (t in tools) {
        add(
            buildJsonObject {
                put("type", JsonPrimitive("function"))
                put(
                    "function",
                    buildJsonObject {
                        put("name", JsonPrimitive(t.name))
                        put("description", JsonPrimitive(t.description))
                        put("parameters", t.inputSchema)
                    },
                )
            },
        )
    }
}

private fun chatMessagesToOpenAiArray(
    systemPrompt: String,
    messages: List<ChatMessage>,
    jsonFmt: Json,
): JsonArray = buildJsonArray {
    if (systemPrompt.isNotBlank()) {
        add(
            buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(systemPrompt))
            },
        )
    }
    for (msg in messages) {
        when (msg.role) {
            ChatRole.Assistant -> add(openAiAssistantMessage(msg, jsonFmt))
            ChatRole.User -> {
                for (piece in openAiExpandUserMessage(msg)) {
                    add(piece)
                }
            }
        }
    }
}

private fun openAiAssistantMessage(msg: ChatMessage, jsonFmt: Json): JsonObject {
    val texts = msg.blocks.filterIsInstance<ContentBlock.Text>().map { it.text }
    val toolUses = msg.blocks.filterIsInstance<ContentBlock.ToolUse>()
    val contentStr = texts.joinToString("\n").trim().ifBlank { null }
    return buildJsonObject {
        put("role", JsonPrimitive("assistant"))
        if (contentStr != null) {
            put("content", JsonPrimitive(contentStr))
        }
        if (toolUses.isNotEmpty()) {
            put(
                "tool_calls",
                buildJsonArray {
                    for (t in toolUses) {
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(t.id))
                                put("type", JsonPrimitive("function"))
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", JsonPrimitive(t.name))
                                        put(
                                            "arguments",
                                            JsonPrimitive(jsonFmt.encodeToString(JsonObject.serializer(), t.input)),
                                        )
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * 展开为本轮 OpenAI `messages` 片段。
 * 顺序：**先**全部 `role:tool`（与 assistant 的 tool_calls 严格对应），**再**追加带 `image_url` 的 `user`
 *（避免在 tool 之前插入 user，导致部分网关校验失败）。
 */
internal fun openAiExpandUserMessage(msg: ChatMessage): List<JsonObject> {
    val out = mutableListOf<JsonObject>()
    val textBuf = StringBuilder()
    val visionChunks = mutableListOf<Pair<String, List<ToolResultImage>>>()

    fun flushText() {
        if (textBuf.isNotEmpty()) {
            out.add(
                buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(textBuf.toString()))
                },
            )
            textBuf.clear()
        }
    }
    for (block in msg.blocks) {
        when (block) {
            is ContentBlock.Text -> {
                if (textBuf.isNotEmpty()) textBuf.append('\n')
                textBuf.append(block.text)
            }
            is ContentBlock.ToolResult -> {
                flushText()
                out.add(
                    buildJsonObject {
                        put("role", JsonPrimitive("tool"))
                        put("tool_call_id", JsonPrimitive(block.toolUseId))
                        put("content", JsonPrimitive(block.content))
                    },
                )
                if (block.images.isNotEmpty()) {
                    visionChunks.add(block.toolUseId to block.images)
                }
            }
            is ContentBlock.ToolUse -> Unit
        }
    }
    flushText()
    if (visionChunks.isNotEmpty()) {
        out.add(openAiVisionFollowUpUserMessage(visionChunks))
    }
    return out
}

private fun openAiVisionFollowUpUserMessage(
    chunks: List<Pair<String, List<ToolResultImage>>>,
): JsonObject {
    val ids = chunks.joinToString(", ") { it.first }
    val contentArr = buildJsonArray {
        for ((_, imgs) in chunks) {
            for (img in imgs) {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("image_url"))
                        put(
                            "image_url",
                            buildJsonObject {
                                put("url", JsonPrimitive(openAiImageDataUrl(img)))
                                put("detail", JsonPrimitive("high"))
                            },
                        )
                    },
                )
            }
        }
        add(
            buildJsonObject {
                put("type", JsonPrimitive("text"))
                put(
                    "text",
                    JsonPrimitive(
                        "上图（或多图）为同一轮工具返回中的设备截图，对应 tool_call_id: $ids。" +
                            "请根据图像直接回答界面内容（含输入框文字等）；勿要求用户代为描述截图。",
                    ),
                )
            },
        )
    }
    return buildJsonObject {
        put("role", JsonPrimitive("user"))
        put("content", contentArr)
    }
}

private fun openAiImageDataUrl(img: ToolResultImage): String {
    val raw = img.base64Data.trim()
    return if (raw.startsWith("data:", ignoreCase = true)) {
        raw
    } else {
        "data:${img.mediaType};base64,$raw"
    }
}
