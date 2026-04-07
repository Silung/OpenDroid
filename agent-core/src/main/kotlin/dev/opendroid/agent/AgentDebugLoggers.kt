package dev.opendroid.agent

/**
 * Debug 构建下可选接入：记录发往 LLM 的完整请求 JSON（可含大块 base64）与原始 SSE 文本。
 *
 * [responseRaw]：成功时为完整 SSE 行；HTTP 失败时为错误响应体（若有）；否则为空字符串。
 */
fun interface LlmTurnTrafficLogger {
    fun onLlmExchange(
        retryAttempt: Int,
        requestBodyJson: String,
        responseRaw: String,
        parsed: AssistantTurnResult?,
        error: Throwable?,
    )
}

/**
 * 记录工具原始结果与截图（base64），由 app 层写入文件。
 *
 * [agentLoopTurn]：与 [OpenDroidQueryLoop] 内每轮模型采样一致（从 1 起），便于与 llm 日志对照。
 */
fun interface AgentToolTrafficLogger {
    fun onToolFinished(
        agentLoopTurn: Int,
        toolName: String,
        toolUseId: String,
        resultText: String,
        images: List<ToolResultImage>,
        ok: Boolean,
    )
}
