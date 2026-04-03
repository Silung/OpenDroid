package dev.opendroid.agent

data class LlmRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>,
    val model: String,
    val maxTokens: Int = 4096,
)

/**
 * One assistant turn: accumulated content blocks and streaming text deltas for UI.
 */
data class AssistantTurnResult(
    val blocks: List<ContentBlock>,
    val stopReason: String?,
    val rawTextForUi: String,
)

interface LlmClient {
    /**
     * Perform a single model sampling round (may include tool_use blocks).
     * Implementations should invoke [onTextDelta] for incremental assistant text only.
     */
    suspend fun completeTurn(
        request: LlmRequest,
        onTextDelta: suspend (String) -> Unit = {},
    ): Result<AssistantTurnResult>
}
