package dev.opendroid.agent

import kotlinx.serialization.json.JsonObject

fun interface ToolExecutor {
    suspend fun execute(toolUseId: String, name: String, input: JsonObject): ToolExecutionResult
}

data class ToolExecutionResult(
    val text: String,
    val isError: Boolean = false,
    val images: List<ToolResultImage> = emptyList(),
)

/**
 * Runs multiple named handlers; unknown tools return an error result.
 */
class CompositeToolExecutor(
    private val handlers: Map<String, suspend (JsonObject) -> ToolExecutionResult>,
) : ToolExecutor {
    override suspend fun execute(toolUseId: String, name: String, input: JsonObject): ToolExecutionResult {
        val handler = handlers[name] ?: return ToolExecutionResult(
            text = "Unknown tool: $name",
            isError = true,
        )
        return try {
            handler(input)
        } catch (e: Exception) {
            ToolExecutionResult(
                text = e.message ?: e.toString(),
                isError = true,
            )
        }
    }
}
