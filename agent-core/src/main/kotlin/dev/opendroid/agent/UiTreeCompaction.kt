package dev.opendroid.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private val compactJson = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
}

/**
 * 压缩 get_ui_tree 的 JSON（去格式化）；超限截断，避免会话与请求体积膨胀。
 */
object UiTreeCompaction {
    const val DEFAULT_STORAGE_MAX_CHARS: Int = 8_000

    /** 截图从发给模型的消息中移除后写入会话的占位（与 [stubHistoricCaptureScreenshotResults] 一致）。 */
    const val DEFAULT_CAPTURE_SCREENSHOT_HISTORY_STUB: String =
        "[OpenDroid] 历史 capture_screenshot 已省略；若需当前画面请再次 capture_screenshot。"

    fun minifyAndCap(raw: String, maxChars: Int): String {
        if (raw.isEmpty()) return raw
        val minified = minifyJsonOrKeep(raw)
        if (minified.length <= maxChars) return minified
        return minified.take(maxChars) +
            "\n…（OpenDroid：已截断至 ${maxChars} 字符，原始约 ${minified.length} 字符）"
    }

    private fun minifyJsonOrKeep(raw: String): String = runCatching {
        when (val el = compactJson.parseToJsonElement(raw)) {
            is JsonObject -> compactJson.encodeToString(JsonObject.serializer(), el)
            is JsonArray -> compactJson.encodeToString(JsonArray.serializer(), el)
            else -> raw.trim()
        }
    }.getOrElse { raw.trim().replace("\r\n", "\n").replace("\n", " ") }

    /**
     * 仅保留**最后一条** get_ui_tree 的 tool_result 正文，更早的换成 [stub]，降低多轮请求 token。
     */
    internal fun stubHistoricGetUiTreeResults(
        messages: List<ChatMessage>,
        stub: String,
    ): List<ChatMessage> {
        data class Pos(val mi: Int, val bi: Int)

        val positions = mutableListOf<Pos>()
        var toolIdToName: Map<String, String> = emptyMap()

        for ((mi, msg) in messages.withIndex()) {
            when (msg.role) {
                ChatRole.Assistant -> {
                    toolIdToName = msg.blocks.filterIsInstance<ContentBlock.ToolUse>().associate { it.id to it.name }
                }
                ChatRole.User -> {
                    msg.blocks.forEachIndexed { bi, block ->
                        if (block is ContentBlock.ToolResult && toolIdToName[block.toolUseId] == "get_ui_tree") {
                            positions.add(Pos(mi, bi))
                        }
                    }
                }
            }
        }
        if (positions.size <= 1) return messages

        val keepPosition = positions.last()
        return messages.mapIndexed { mi, msg ->
            if (msg.role != ChatRole.User) return@mapIndexed msg
            val newBlocks = msg.blocks.mapIndexed { bi, block ->
                val p = Pos(mi, bi)
                if (block is ContentBlock.ToolResult &&
                    positions.contains(p) &&
                    p != keepPosition
                ) {
                    ContentBlock.ToolResult(block.toolUseId, stub, block.isError)
                } else {
                    block
                }
            }
            if (newBlocks == msg.blocks) msg else ChatMessage(ChatRole.User, newBlocks)
        }
    }

    /**
     * 仅保留**最后一条** capture_screenshot 的图片与正文，更早的换成 [stub] 并去掉 [ContentBlock.ToolResult.images]。
     */
    internal fun stubHistoricCaptureScreenshotResults(
        messages: List<ChatMessage>,
        stub: String,
    ): List<ChatMessage> {
        data class Pos(val mi: Int, val bi: Int)
        val positions = mutableListOf<Pos>()
        var toolIdToName: Map<String, String> = emptyMap()
        for ((mi, msg) in messages.withIndex()) {
            when (msg.role) {
                ChatRole.Assistant -> {
                    toolIdToName = msg.blocks.filterIsInstance<ContentBlock.ToolUse>().associate { it.id to it.name }
                }
                ChatRole.User -> {
                    msg.blocks.forEachIndexed { bi, block ->
                        if (block is ContentBlock.ToolResult && toolIdToName[block.toolUseId] == "capture_screenshot") {
                            positions.add(Pos(mi, bi))
                        }
                    }
                }
            }
        }
        if (positions.size <= 1) return messages
        val keepPosition = positions.last()
        return messages.mapIndexed { mi, msg ->
            if (msg.role != ChatRole.User) return@mapIndexed msg
            val newBlocks = msg.blocks.mapIndexed { bi, block ->
                val p = Pos(mi, bi)
                if (block is ContentBlock.ToolResult &&
                    positions.contains(p) &&
                    p != keepPosition
                ) {
                    ContentBlock.ToolResult(block.toolUseId, stub, block.isError)
                } else {
                    block
                }
            }
            if (newBlocks == msg.blocks) msg else ChatMessage(ChatRole.User, newBlocks)
        }
    }

    /**
     * 模型已读过带图的 `capture_screenshot` tool_result 之后，从 [conversation] 中去掉 JPEG，
     * 避免同一轮 agent 循环里后续 completeTurn 仍把旧截图当作当前界面反复发送。
     */
    internal fun stripConsumedCaptureScreenshots(
        conversation: MutableList<ChatMessage>,
        stub: String = DEFAULT_CAPTURE_SCREENSHOT_HISTORY_STUB,
    ) {
        var toolIdToName: Map<String, String> = emptyMap()
        for (i in conversation.indices) {
            val msg = conversation[i]
            when (msg.role) {
                ChatRole.Assistant -> {
                    toolIdToName =
                        msg.blocks.filterIsInstance<ContentBlock.ToolUse>().associate { it.id to it.name }
                }
                ChatRole.User -> {
                    val newBlocks = msg.blocks.map { block ->
                        if (block is ContentBlock.ToolResult &&
                            toolIdToName[block.toolUseId] == "capture_screenshot" &&
                            block.images.isNotEmpty()
                        ) {
                            ContentBlock.ToolResult(block.toolUseId, stub, block.isError)
                        } else {
                            block
                        }
                    }
                    if (newBlocks != msg.blocks) {
                        conversation[i] = ChatMessage(ChatRole.User, newBlocks)
                    }
                }
            }
        }
    }
}
