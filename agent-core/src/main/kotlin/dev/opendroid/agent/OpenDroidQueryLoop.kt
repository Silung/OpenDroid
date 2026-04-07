package dev.opendroid.agent

import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

sealed class QueryLoopEvent {
    data class UserTurnAdded(val preview: String) : QueryLoopEvent()
    data class AssistantDelta(val text: String) : QueryLoopEvent()
    data class AssistantTurnFinished(val text: String, val hadToolCalls: Boolean) : QueryLoopEvent()
    data class ToolStarted(val name: String, val toolUseId: String) : QueryLoopEvent()
    data class ToolFinished(
        val name: String,
        val toolUseId: String,
        val inputSummary: String,
        val ok: Boolean,
        val durationMs: Long,
        val resultTotalChars: Int,
        /** Substring for UI. get_ui_tree 会话内会再压缩；历史轮次在请求模型前会替换为占位。 */
        val resultPreview: String,
        /** capture_screenshot 等：与发往模型相同的图，供界面缩略图。 */
        val resultImages: List<ToolResultImage> = emptyList(),
    ) : QueryLoopEvent()

    /** 估算负载过大时，已用模型将更早消息压缩为摘要（见 [ConversationCompactConfig]）。 */
    data class ConversationCompacted(val approxPayloadCharsBefore: Int, val summaryCharCount: Int) : QueryLoopEvent()

    data class Finished(val reason: FinishReason) : QueryLoopEvent()
}

sealed class FinishReason {
    data object Normal : FinishReason()
    data object MaxTurns : FinishReason()
    data class Error(val message: String) : FinishReason()
}

/**
 * Anthropic-style agent loop: append user text → model → if tool_use, execute tools → tool_result user
 * message → repeat until no tool calls or [maxTurns]（与 [claude-code] `queryLoop` 类似）。
 * 连续、标记为无冲突的只读类工具（见 [OpenDroidToolConcurrency]）在同一轮内并发执行。
 */
class OpenDroidQueryLoop(
    private val llm: LlmClient,
    private val tools: List<ToolDefinition>,
    private val toolExecutor: ToolExecutor,
    private val toolTrafficLogger: AgentToolTrafficLogger? = null,
    private val toolResultUiPreviewChars: Int = 14_000,
    /** 写入会话的 get_ui_tree 正文上限（minify 后再截断）。 */
    private val getUiTreeStorageMaxChars: Int = UiTreeCompaction.DEFAULT_STORAGE_MAX_CHARS,
    /** 发往 API 前对每条 tool_result 文本的额外预算（在 get_ui_tree minify 之后仍可能超大，如 list_apps / load_skill）。 */
    private val toolResultBudgetPolicy: ToolResultBudgetPolicy = ToolResultBudgetPolicy(),
    private val conversationCompactConfig: ConversationCompactConfig = ConversationCompactConfig(),
    /** 发往模型时，除**最后一条** get_ui_tree 外，历史 tool_result 替换为该占位（极短）。 */
    private val getUiTreeHistoryStub: String = "[OpenDroid] 历史 get_ui_tree 已省略以省 token；若需该屏信息请再次 get_ui_tree。",
    /** 已从会话中移除截图 blob 后写入的占位（每轮 completeTurn 后剥离 JPEG，避免重复发送）。 */
    private val captureScreenshotHistoryStub: String = UiTreeCompaction.DEFAULT_CAPTURE_SCREENSHOT_HISTORY_STUB,
    /**
     * 每轮 [LlmClient.completeTurn] 时，发往模型的上下文中至多保留最近这么多条 **Assistant**；
     * User 条数不设此上限（随前缀截断自然变化）。见 [sliceChatMessagesForLlmRequest]。
     */
    private val maxLlmHistoryAssistantMessages: Int = 6,
) {
    private val loopJson = Json { prettyPrint = false; ignoreUnknownKeys = true }

    companion object {
        private const val COMPACT_SYSTEM_PROMPT =
            "You compress prior chat for a phone UI automation agent. Output a concise bullet summary covering: " +
                "user goals, important tool calls and outcomes (get_ui_tree, taps, apps, errors), " +
                "and current task state if clear. Match the transcript language (Chinese or English). Do not invent facts."
    }

    private fun summarizeToolInput(input: JsonObject, maxChars: Int = 2_000): String {
        val raw = loopJson.encodeToString(JsonObject.serializer(), input)
        return if (raw.length <= maxChars) {
            raw
        } else {
            raw.take(maxChars) + "\n…（输入已截断，共 ${raw.length} 字符）"
        }
    }

    private suspend fun runSingleToolUse(
        tool: ContentBlock.ToolUse,
        agentLoopTurn: Int,
        emit: suspend (QueryLoopEvent) -> Unit,
    ): ContentBlock.ToolResult {
        val inputSummary = summarizeToolInput(tool.input)
        emit(QueryLoopEvent.ToolStarted(tool.name, tool.id))
        val t0 = System.nanoTime()
        val exec = toolExecutor.execute(tool.id, tool.name, tool.input)
        val durationMs = (System.nanoTime() - t0) / 1_000_000L
        toolTrafficLogger?.onToolFinished(
            agentLoopTurn,
            tool.name,
            tool.id,
            exec.text,
            exec.images,
            ok = !exec.isError,
        )
        val text = exec.text
        val textForConversation = if (tool.name == "get_ui_tree" && !exec.isError) {
            UiTreeCompaction.minifyAndCap(text, getUiTreeStorageMaxChars)
        } else {
            text
        }
        val previewCap = toolResultUiPreviewChars.coerceAtLeast(500)
        val preview = if (exec.images.isNotEmpty()) {
            val meta = if (text.length <= 600) text else text.take(600) + "…"
            val n = exec.images.size
            val head =
                if (n == 1) {
                    "JPEG 已附加在请求中。以下为工具返回的元数据（宽/高等），完整画面见应用内缩略图。"
                } else {
                    "已附加 $n 张 JPEG。以下为元数据摘要，画面见下方缩略图。"
                }
            "$head\n\n$meta"
        } else if (text.length <= previewCap) {
            text
        } else {
            text.take(previewCap) +
                "\n…（结果已截断用于界面展示，共 ${text.length} 字符；会话内已压缩见下轮请求）"
        }
        emit(
            QueryLoopEvent.ToolFinished(
                name = tool.name,
                toolUseId = tool.id,
                inputSummary = inputSummary,
                ok = !exec.isError,
                durationMs = durationMs,
                resultTotalChars = text.length,
                resultPreview = preview,
                resultImages = exec.images,
            ),
        )
        return ContentBlock.ToolResult(
            toolUseId = tool.id,
            content = textForConversation,
            isError = exec.isError,
            images = exec.images,
        )
    }

    private fun buildMessagesForLlm(
        conversation: List<ChatMessage>,
    ): List<ChatMessage> {
        val stubbed = UiTreeCompaction.stubHistoricCaptureScreenshotResults(
            UiTreeCompaction.stubHistoricGetUiTreeResults(
                conversation,
                getUiTreeHistoryStub,
            ),
            captureScreenshotHistoryStub,
        )
        return stubbed.applyToolResultBudget(toolResultBudgetPolicy)
    }

    private suspend fun maybeCompactConversation(
        systemPrompt: String,
        conversation: MutableList<ChatMessage>,
        model: String,
        maxTokens: Int,
        emit: suspend (QueryLoopEvent) -> Unit,
    ) {
        val cfg = conversationCompactConfig
        if (!cfg.enabled) return
        if (conversation.size <= cfg.keepRecentMessages) return

        val trialMessages = buildMessagesForLlm(conversation.toList())
        var est = RequestPayloadEstimator.approximatePayloadChars(systemPrompt, trialMessages, tools)
        if (est <= cfg.triggerApproxPayloadChars) return

        val headSize = conversation.size - cfg.keepRecentMessages
        if (headSize <= 0) return

        val head = conversation.take(headSize)
        val tail = conversation.drop(headSize)
        val digest = head.toCompactDigestText()
        if (digest.isBlank()) return

        val summaryResult = llm.completeTurn(
            LlmRequest(
                systemPrompt = COMPACT_SYSTEM_PROMPT,
                messages = listOf(
                    ChatMessage(
                        ChatRole.User,
                        listOf(ContentBlock.Text("[OpenDroid：以下是待压缩的早期对话节选]\n$digest")),
                    ),
                ),
                tools = emptyList(),
                model = model,
                maxTokens = min(maxTokens, cfg.summaryMaxTokens),
            ),
            onTextDelta = { },
        ).getOrElse { return }

        val summaryText = summaryResult.blocks
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (summaryText.isEmpty()) return

        val summaryMsg = ChatMessage(
            ChatRole.User,
            listOf(
                ContentBlock.Text(
                    "[OpenDroid 上文摘要]\n$summaryText",
                ),
            ),
        )
        conversation.clear()
        conversation.add(summaryMsg)
        conversation.addAll(tail)
        emit(QueryLoopEvent.ConversationCompacted(est, summaryText.length))
    }

    suspend fun run(
        systemPrompt: String,
        conversation: MutableList<ChatMessage>,
        userText: String,
        model: String,
        maxTurns: Int = 32,
        maxTokens: Int = 4096,
        emit: suspend (QueryLoopEvent) -> Unit,
    ) {
        try {
            val userBlocks = listOf(ContentBlock.Text(userText))
            conversation.add(ChatMessage(ChatRole.User, userBlocks))
            /** 本轮用户自然语言在 [conversation] 中的下标；多轮 tool 期间不变，供切片固定保留。 */
            val pinnedUserMessageIndex = conversation.lastIndex
            emit(QueryLoopEvent.UserTurnAdded(userText.take(200)))

            var turnIndex = 0
            while (true) {
                if (turnIndex >= maxTurns) {
                    emit(QueryLoopEvent.Finished(FinishReason.MaxTurns))
                    return
                }
                turnIndex++
                maybeCompactConversation(systemPrompt, conversation, model, maxTokens, emit)
                val messagesForLlm = buildMessagesForLlm(
                    sliceChatMessagesForLlmRequest(
                        messages = conversation.toList(),
                        maxAssistantMessages = maxLlmHistoryAssistantMessages,
                        firstMessageIndexToKeep = pinnedUserMessageIndex,
                    ),
                )
                val turnResult = llm.completeTurn(
                    LlmRequest(
                        systemPrompt = systemPrompt,
                        messages = messagesForLlm,
                        tools = tools,
                        model = model,
                        maxTokens = maxTokens,
                    ),
                    onTextDelta = { delta ->
                        emit(QueryLoopEvent.AssistantDelta(delta))
                    },
                )
                val assistantTurn = turnResult.getOrElse { e ->
                    emit(QueryLoopEvent.Finished(FinishReason.Error(e.message ?: e.toString())))
                    return
                }

                UiTreeCompaction.stripConsumedCaptureScreenshots(
                    conversation,
                    captureScreenshotHistoryStub,
                )
                conversation.add(
                    ChatMessage(ChatRole.Assistant, assistantTurn.blocks),
                )
                val toolBlocks = assistantTurn.blocks.filterIsInstance<ContentBlock.ToolUse>()
                emit(
                    QueryLoopEvent.AssistantTurnFinished(
                        text = assistantTurn.rawTextForUi,
                        hadToolCalls = toolBlocks.isNotEmpty(),
                    ),
                )

                if (toolBlocks.isEmpty()) {
                    emit(QueryLoopEvent.Finished(FinishReason.Normal))
                    return
                }

                val resultBlocks = mutableListOf<ContentBlock>()
                for (batch in OpenDroidToolConcurrency.partitionConsecutiveSafeBatches(toolBlocks)) {
                    if (batch.size <= 1) {
                        resultBlocks.add(runSingleToolUse(batch.single(), turnIndex, emit))
                    } else {
                        coroutineScope {
                            val parts = batch.map { tool ->
                                async { runSingleToolUse(tool, turnIndex, emit) }
                            }.awaitAll()
                            resultBlocks.addAll(parts)
                        }
                    }
                }
                conversation.add(ChatMessage(ChatRole.User, resultBlocks))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(QueryLoopEvent.Finished(FinishReason.Error(e.message ?: e.toString())))
        }
    }
}
