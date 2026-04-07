package dev.opendroid.app.debug

import android.content.Context
import android.util.Base64
import dev.opendroid.agent.AgentToolTrafficLogger
import dev.opendroid.agent.AssistantTurnResult
import dev.opendroid.agent.ContentBlock
import dev.opendroid.agent.LlmTurnTrafficLogger
import dev.opendroid.agent.ToolResultImage
import dev.opendroid.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * **仅 debug 构建**：将单次用户任务内的 LLM 完整请求/原始 SSE、解析结果，以及工具原文与截图落盘。
 *
 * 目录：`filesDir/debug_agent_traces/run_<时间>_<会话id>/`
 */
class DebugAgentTrace private constructor(
    private val dir: File,
) : LlmTurnTrafficLogger, AgentToolTrafficLogger {

    private val writeLock = Any()
    private val llmSeq = AtomicInteger(0)
    private val toolSeq = AtomicInteger(0)

    private val pretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    override fun onLlmExchange(
        retryAttempt: Int,
        requestBodyJson: String,
        responseRaw: String,
        parsed: AssistantTurnResult?,
        error: Throwable?,
    ) {
        synchronized(writeLock) {
            val n = llmSeq.getAndIncrement()
            val prefix = String.format(Locale.US, "llm_%04d_attempt%d", n, retryAttempt)
            File(dir, "${prefix}_request.json").writeText(requestBodyJson)
            File(dir, "${prefix}_response_raw.txt").writeText(responseRaw)
            if (error != null) {
                File(dir, "${prefix}_error.txt").writeText(
                    buildString {
                        appendLine(error.javaClass.name)
                        appendLine(error.message)
                        appendLine()
                        append(error.stackTraceToString())
                    },
                )
            }
            if (parsed != null) {
                File(dir, "${prefix}_response_parsed.json").writeText(parsedAssistantJson(parsed))
            }
        }
    }

    override fun onToolFinished(
        agentLoopTurn: Int,
        toolName: String,
        toolUseId: String,
        resultText: String,
        images: List<ToolResultImage>,
        ok: Boolean,
    ) {
        synchronized(writeLock) {
            val n = toolSeq.getAndIncrement()
            val safeName = safeFilePart(toolName)
            val safeId = safeFilePart(toolUseId)
            val prefix = String.format(Locale.US, "tool_%04d_turn%d_%s_%s", n, agentLoopTurn, safeName, safeId)
            File(dir, "${prefix}_result.txt").writeText(
                buildString {
                    appendLine("ok=$ok")
                    appendLine("--- body ---")
                    append(resultText)
                },
            )
            images.forEachIndexed { i, img ->
                val ext = when {
                    img.mediaType.contains("png", ignoreCase = true) -> "png"
                    img.mediaType.contains("webp", ignoreCase = true) -> "webp"
                    else -> "jpg"
                }
                runCatching {
                    val bytes = Base64.decode(img.base64Data, Base64.DEFAULT)
                    File(dir, "${prefix}_img_$i.$ext").writeBytes(bytes)
                }
            }
        }
    }

    private fun parsedAssistantJson(r: AssistantTurnResult): String {
        val blocks = buildJsonArray {
            for (b in r.blocks) {
                when (b) {
                    is ContentBlock.Text -> add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(b.text))
                        },
                    )
                    is ContentBlock.ToolUse -> add(
                        buildJsonObject {
                            put("type", JsonPrimitive("tool_use"))
                            put("id", JsonPrimitive(b.id))
                            put("name", JsonPrimitive(b.name))
                            put("input", b.input)
                        },
                    )
                    is ContentBlock.ToolResult -> add(
                        buildJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(b.toolUseId))
                            put("content_preview", JsonPrimitive(b.content.take(4_000)))
                            put("images_count", JsonPrimitive(b.images.size))
                        },
                    )
                }
            }
        }
        val root = buildJsonObject {
            put("stop_reason", JsonPrimitive(r.stopReason ?: ""))
            put("raw_text_for_ui", JsonPrimitive(r.rawTextForUi))
            put("blocks", blocks)
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    companion object {
        fun tryStartTrace(context: Context, chatSessionId: String): DebugAgentTrace? {
            if (!BuildConfig.DEBUG) return null
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val sid = chatSessionId.filter { it.isLetterOrDigit() || it == '-' }.take(12).ifBlank { "session" }
            val dir = File(context.filesDir, "debug_agent_traces/run_${ts}_$sid").apply { mkdirs() }
            File(dir, "README.txt").writeText(
                """
                OpenDroid DEBUG 原始轨迹（完整 LLM 请求 JSON、原始 SSE、解析后的 assistant 块，以及工具返回与截图文件）。
                请求体可能含 base64 大图，文件体积会很大。
                - llm_*_request.json / response_raw.txt / response_parsed.json / error.txt
                - tool_*_result.txt 与 tool_*_img_0.jpg 等
                """.trimIndent() + "\n",
            )
            return DebugAgentTrace(dir)
        }
    }
}

private fun safeFilePart(s: String): String =
    s.replace(Regex("[^a-zA-Z0-9._-]+"), "_").take(48).ifBlank { "x" }
