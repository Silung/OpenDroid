package dev.opendroid.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 发往 API 前的内容预算（与 claude-code `applyToolResultBudget` 同目的：控制请求体与 token）。
 */
data class ToolResultBudgetPolicy(
    val maxTextCharsPerToolResult: Int = 12_000,
    /** 单条 `text` 类型 content 的上限（用户/助手的长文）；避免与 tool_result 无关的爆量。 */
    val maxCharsPerTextBlock: Int = 20_000,
)

/**
 * 当估算的 API 负载超过 [triggerApproxPayloadChars] 时，将**当前会话中的全部消息**摘要为
 * 一条用户消息（另一次模型调用），并**替换**原列表（实现见 `OpenDroidQueryLoop`）。
 */
data class ConversationCompactConfig(
    val enabled: Boolean = true,
    /**
     * 粗算字符数超过则触发摘要。决策体积见 [RequestPayloadEstimator.approximateCompactionTriggerPayloadChars]：
     * **不以** tool_result 内嵌图 base64 真实长度计入，避免单次截图误触压缩。
     */
    val triggerApproxPayloadChars: Int = 90_000,
    /** 摘要请求的 max_tokens 上限 */
    val summaryMaxTokens: Int = 2_048,
)

private val budgetJson = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
}

fun List<ChatMessage>.applyToolResultBudget(policy: ToolResultBudgetPolicy): List<ChatMessage> {
    val toolCap = policy.maxTextCharsPerToolResult.coerceAtLeast(400)
    val textCapLimit = policy.maxCharsPerTextBlock.coerceAtLeast(0)
    return map { msg ->
        val newBlocks = msg.blocks.map { block ->
            when (block) {
                is ContentBlock.Text -> {
                    if (textCapLimit <= 0 || block.text.length <= textCapLimit) {
                        block
                    } else {
                        val marker =
                            "\n…（OpenDroid：text 块已超过字符预算；原始约 ${block.text.length} 字符。）"
                        val reserve = marker.length.coerceAtMost(textCapLimit / 2).coerceAtLeast(40)
                        val take = (textCapLimit - reserve).coerceAtLeast(200)
                        ContentBlock.Text(block.text.take(take) + marker)
                    }
                }
                is ContentBlock.ToolResult -> {
                    val marker =
                        "\n…（OpenDroid：tool_result 已超过单条字符预算并截断；原始约 ${block.content.length} 字符。）"
                    val reserve = marker.length.coerceAtMost(toolCap / 2).coerceAtLeast(40)
                    val textCap = (toolCap - reserve).coerceAtLeast(200)
                    if (block.content.length <= textCap) {
                        block
                    } else {
                        ContentBlock.ToolResult(
                            toolUseId = block.toolUseId,
                            content = block.content.take(textCap) + marker,
                            isError = block.isError,
                            images = block.images,
                        )
                    }
                }
                else -> block
            }
        }
        if (newBlocks == msg.blocks) msg else ChatMessage(msg.role, newBlocks)
    }
}

/** 粗算即将序列化到 Messages API 的体积（用于触发 compact / 观测；非官方 tokenizer）。 */
object RequestPayloadEstimator {
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    fun approximatePayloadChars(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ): Int {
        var n = systemPrompt.length + 256
        for (t in tools) {
            n += t.name.length + t.description.length +
                json.encodeToString(JsonObject.serializer(), t.inputSchema).length + 48
        }
        for (msg in messages) {
            n += 24
            for (block in msg.blocks) {
                n += blockWeight(block)
            }
        }
        return n
    }

    /**
     * 仅用于 [ConversationCompactConfig]：是否要把整段会话压成一条摘要。
     *
     * **不把** [ContentBlock.ToolResult.images] 的 base64 按字节长度计入。否则在刚插入
     * `capture_screenshot` 的 tool_result 后、尚未 [UiTreeCompaction.stripConsumedCaptureScreenshots]
     * 之前，单次 JPEG 就会让 [approximatePayloadChars] 暴涨并**误触发**全文压缩；多模态体积不应等同于「文字历史过长」。
     *
     * @param charsPerToolResultImagePlaceholder 每张内嵌图在决策里占的固定「名义」字符（沿用 mediaType 的小额开销）。
     */
    fun approximateCompactionTriggerPayloadChars(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        charsPerToolResultImagePlaceholder: Int = 512,
    ): Int {
        val perImg = charsPerToolResultImagePlaceholder.coerceAtLeast(0)
        var n = systemPrompt.length + 256
        for (t in tools) {
            n += t.name.length + t.description.length +
                json.encodeToString(JsonObject.serializer(), t.inputSchema).length + 48
        }
        for (msg in messages) {
            n += 24
            for (block in msg.blocks) {
                n += when (block) {
                    is ContentBlock.Text -> block.text.length + 24
                    is ContentBlock.ToolUse -> {
                        block.name.length + block.id.length +
                            json.encodeToString(JsonObject.serializer(), block.input).length + 48
                    }
                    is ContentBlock.ToolResult -> {
                        var x = block.content.length + block.toolUseId.length + 40
                        for (img in block.images) {
                            x += perImg + img.mediaType.length + 48
                        }
                        x
                    }
                }
            }
        }
        return n
    }

    private fun blockWeight(block: ContentBlock): Int = when (block) {
        is ContentBlock.Text -> block.text.length + 24
        is ContentBlock.ToolUse -> {
            block.name.length + block.id.length +
                json.encodeToString(JsonObject.serializer(), block.input).length + 48
        }
        is ContentBlock.ToolResult -> {
            var x = block.content.length + block.toolUseId.length + 40
            for (img in block.images) {
                x += img.base64Data.length + img.mediaType.length + 48
            }
            x
        }
    }
}

/**
 * 将消息列表压成可读 transcript，供摘要模型使用（大段会截断防溢出）。
 */
fun List<ChatMessage>.toCompactDigestText(
    maxTotalChars: Int = 120_000,
    maxTextPerBlock: Int = 3_500,
    maxToolInputChars: Int = 1_500,
): String {
    val sb = StringBuilder()
    for (msg in this) {
        if (sb.length >= maxTotalChars) break
        sb.append("#### ").append(msg.role).append("\n")
        for (block in msg.blocks) {
            if (sb.length >= maxTotalChars) break
            when (block) {
                is ContentBlock.Text -> {
                    val t = block.text
                    if (t.length <= maxTextPerBlock) {
                        sb.append(t).append('\n')
                    } else {
                        sb.append(t.take(maxTextPerBlock)).append("…\n")
                    }
                }
                is ContentBlock.ToolUse -> {
                    val inp = budgetJson.encodeToString(JsonObject.serializer(), block.input)
                    val i = if (inp.length <= maxToolInputChars) inp else inp.take(maxToolInputChars) + "…"
                    sb.append("tool_use ").append(block.name).append(" id=").append(block.id)
                        .append(" input=").append(i).append('\n')
                }
                is ContentBlock.ToolResult -> {
                    val c = block.content
                    val body = if (c.length <= maxTextPerBlock) c else c.take(maxTextPerBlock) + "(${c.length} chars total)"
                    sb.append("tool_result ").append(block.toolUseId).append(":\n")
                        .append(body).append('\n')
                    if (block.images.isNotEmpty()) {
                        sb.append("(tool_result has ").append(block.images.size).append(" image(s))\n")
                    }
                }
            }
        }
        sb.append('\n')
    }
    return sb.toString()
}
