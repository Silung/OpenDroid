package dev.opendroid.agent

class ScriptedLlmClient(
    private val scriptedTurns: List<AssistantTurnResult>,
) : LlmClient {
    private var index = 0

    override suspend fun completeTurn(
        request: LlmRequest,
        onTextDelta: suspend (String) -> Unit,
    ): Result<AssistantTurnResult> {
        if (index >= scriptedTurns.size) {
            return Result.failure(IllegalStateException("ScriptedLlmClient exhausted at turn $index"))
        }
        val turn = scriptedTurns[index++]
        for (b in turn.blocks) {
            if (b is ContentBlock.Text) {
                onTextDelta(b.text)
            }
        }
        return Result.success(turn)
    }
}
