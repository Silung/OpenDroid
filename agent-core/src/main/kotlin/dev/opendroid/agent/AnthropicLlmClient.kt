package dev.opendroid.agent

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Streaming client for Anthropic Messages API ([docs](https://docs.anthropic.com/en/api/messages)).
 *
 * API 错误处理参考 claude-code `withRetry.ts` / `claude.ts`：
 * - 对 429、408、409、5xx、连接类 IOException 做有限次退避重试（HTTP `Retry-After` 头优先；否则尝试 JSON 体内的 `retry_after` 秒）。
 * - 解析 JSON 错误体中的 `error.message` / `request_id`，便于排查。
 * - 流式响应中的 `overloaded` / rate limit 类文案也允许重试（与 TS 侧对 529/overloaded 的特别处理类似）。
 */
class AnthropicLlmClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val anthropicVersion: String = "2023-06-01",
    client: OkHttpClient? = null,
    maxRetries: Int = DEFAULT_MAX_RETRIES,
) : LlmClient {

    private val maxRetries = maxRetries.coerceAtLeast(1)

    private val http = client ?: OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun completeTurn(
        request: LlmRequest,
        onTextDelta: suspend (String) -> Unit,
    ): Result<AssistantTurnResult> = withContext(Dispatchers.IO) {
        val httpReq = buildHttpRequest(request)
        var lastError: Exception? = null
        repeat(maxRetries) { attemptIndex ->
            try {
                val result = executeStreamingTurn(httpReq, onTextDelta)
                return@withContext Result.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AnthropicApiException) {
                lastError = e
                val isLast = attemptIndex >= maxRetries - 1
                if (!shouldRetryAnthropic(e) || isLast) {
                    return@withContext Result.failure(e)
                }
                coroutineContext.ensureActive()
                delay(computeRetryDelayMs(attemptIndex + 1, e.retryAfterMillisHint))
            } catch (e: IOException) {
                lastError = e
                val isLast = attemptIndex >= maxRetries - 1
                if (isLast) {
                    return@withContext Result.failure(e)
                }
                coroutineContext.ensureActive()
                delay(computeRetryDelayMs(attemptIndex + 1, null))
            } catch (e: Exception) {
                try {
                    coroutineContext.ensureActive()
                } catch (ce: CancellationException) {
                    throw ce
                }
                return@withContext Result.failure(e)
            }
        }
        Result.failure(lastError ?: IllegalStateException("Anthropic: exhausted retries"))
    }

    private fun buildHttpRequest(request: LlmRequest): Request {
        val bodyJson = buildJsonObject {
            put("model", JsonPrimitive(request.model))
            put("max_tokens", JsonPrimitive(request.maxTokens))
            put("stream", JsonPrimitive(true))
            put("system", JsonPrimitive(request.systemPrompt))
            put("messages", request.messages.toAnthropicMessagesArray())
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        for (t in request.tools) {
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive(t.name))
                                    put("description", JsonPrimitive(t.description))
                                    put("input_schema", t.inputSchema)
                                },
                            )
                        }
                    },
                )
            }
        }
        return Request.Builder()
            .url("$baseUrl/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", anthropicVersion)
            .addHeader("content-type", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), bodyJson).toRequestBody(JSON_MEDIA))
            .build()
    }

    private suspend fun executeStreamingTurn(
        httpReq: Request,
        onTextDelta: suspend (String) -> Unit,
    ): AssistantTurnResult {
        val call = http.newCall(httpReq)
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                val parsed = parseAnthropicErrorBody(errBody)
                val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
                val retryFromHeaderMs = retryAfterSec?.times(1000)
                val retryHintMs = retryFromHeaderMs ?: parsed.retryAfterMillisFromBody
                throw AnthropicApiException(
                    statusCode = response.code,
                    message = formatHttpApiError(response.code, parsed.message, parsed.requestId),
                    requestId = parsed.requestId,
                    retryAfterMillisHint = retryHintMs,
                )
            }
            val body = response.body
                ?: throw AnthropicApiException(0, "Anthropic: empty response body", null, null)
            body.byteStream().bufferedReader().use { reader ->
                return parseSseLines(reader.lineSequence(), onTextDelta)
            }
        }
    }

    private data class ParsedAnthropicErrorBody(
        val message: String,
        val requestId: String?,
        /** JSON 内 `retry_after`（秒），与 HTTP `Retry-After` 头择一使用（头优先）。 */
        val retryAfterMillisFromBody: Long?,
    )

    private fun parseAnthropicErrorBody(raw: String): ParsedAnthropicErrorBody {
        if (raw.isBlank()) {
            return ParsedAnthropicErrorBody("（无响应体）", null, null)
        }
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val err = root["error"]?.jsonObject
            val msg = err?.get("message")?.jsonPrimitive?.content
                ?: root["message"]?.jsonPrimitive?.content
                ?: raw.take(600)
            val reqId = err?.get("request_id")?.jsonPrimitive?.content
                ?: root["request_id"]?.jsonPrimitive?.content
            val retryMs = extractRetryAfterMillis(root, err)
            ParsedAnthropicErrorBody(msg, reqId, retryMs)
        } catch (_: Exception) {
            ParsedAnthropicErrorBody(raw.take(600), null, null)
        }
    }

    private fun extractRetryAfterMillis(root: JsonObject, err: JsonObject?): Long? {
        fun fromObject(o: JsonObject): Long? {
            val p = o["retry_after"]?.jsonPrimitive ?: return null
            p.intOrNull?.let { return it.coerceAtLeast(0) * 1000L }
            p.longOrNull?.let { return it.coerceAtLeast(0L) * 1000L }
            val sec = p.content.toDoubleOrNull() ?: return null
            return (sec.coerceAtLeast(0.0) * 1000.0).toLong()
        }
        return fromObject(root) ?: err?.let { fromObject(it) }
    }

    private fun formatHttpApiError(code: Int, apiMessage: String, requestId: String?): String {
        val suffix = requestId?.let { " request_id=$it" }.orEmpty()
        return "Anthropic HTTP $code$suffix: $apiMessage"
    }

    private suspend fun parseSseLines(
        lines: Sequence<String>,
        onTextDelta: suspend (String) -> Unit,
    ): AssistantTurnResult {
        val accumulators = mutableMapOf<Int, BlockAccumulator>()
        var stopReason: String? = null
        val uiSb = StringBuilder()
        var lineTick = 0

        for (line in lines) {
            if (lineTick++ % 32 == 0) coroutineContext.ensureActive()
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue

            val element = try {
                json.parseToJsonElement(payload).jsonObject
            } catch (_: Exception) {
                continue
            }

            when (val type = element["type"]?.jsonPrimitive?.content) {
                "error" -> {
                    val err = element["error"]?.jsonObject
                    val errType = err?.get("type")?.jsonPrimitive?.content
                    val msg = err?.get("message")?.jsonPrimitive?.content ?: payload
                    val reqId = err?.get("request_id")?.jsonPrimitive?.content
                    val detail = errType?.let { "$it: " }.orEmpty() + msg
                    throw AnthropicApiException(
                        statusCode = 0,
                        message = "Anthropic stream error: $detail",
                        requestId = reqId,
                        retryAfterMillisHint = null,
                    )
                }
                "content_block_start" -> {
                    val index = element.indexInt("index")
                    val block = element["content_block"]?.jsonObject ?: continue
                    accumulators[index] = when (block["type"]?.jsonPrimitive?.content) {
                        "text" -> BlockAccumulator.TextAcc()
                        "tool_use" -> BlockAccumulator.ToolAcc(
                            id = block.requireText("id"),
                            name = block.requireText("name"),
                        )
                        else -> BlockAccumulator.Skip
                    }
                }
                "content_block_delta" -> {
                    val index = element.indexInt("index")
                    val delta = element["delta"]?.jsonObject ?: continue
                    when (delta["type"]?.jsonPrimitive?.content) {
                        "text_delta" -> {
                            val acc = accumulators[index] as? BlockAccumulator.TextAcc ?: continue
                            val piece = delta["text"]?.jsonPrimitive?.content.orEmpty()
                            acc.text.append(piece)
                            uiSb.append(piece)
                            onTextDelta(piece)
                        }
                        "input_json_delta" -> {
                            val acc = accumulators[index] as? BlockAccumulator.ToolAcc ?: continue
                            acc.inputJson.append(delta["partial_json"]?.jsonPrimitive?.content.orEmpty())
                        }
                    }
                }
                "message_delta" -> {
                    val delta = element["delta"]?.jsonObject ?: continue
                    delta["stop_reason"]?.jsonPrimitive?.content?.let { stopReason = it }
                }
                else -> { /* ping, message_start, etc. */ }
            }
        }

        val blocks = accumulators.toSortedMap().values.mapNotNull { it.toContentBlock(json) }
        return AssistantTurnResult(
            blocks = blocks,
            stopReason = stopReason,
            rawTextForUi = uiSb.toString(),
        )
    }

    private fun JsonObject.indexInt(key: String): Int {
        val p = get(key)?.jsonPrimitive ?: error("missing $key")
        return p.intOrNull ?: p.content.toInt()
    }

    private fun JsonObject.requireText(key: String): String =
        get(key)?.jsonPrimitive?.content ?: error("missing $key")

    private sealed class BlockAccumulator {
        object Skip : BlockAccumulator() {
            override fun toContentBlock(json: Json): ContentBlock? = null
        }

        class TextAcc(val text: StringBuilder = StringBuilder()) : BlockAccumulator() {
            override fun toContentBlock(json: Json): ContentBlock =
                ContentBlock.Text(text.toString())
        }

        class ToolAcc(
            val id: String,
            val name: String,
            val inputJson: StringBuilder = StringBuilder(),
        ) : BlockAccumulator() {
            override fun toContentBlock(json: Json): ContentBlock {
                val inputStr = inputJson.toString().trim().ifEmpty { "{}" }
                val inputObj = try {
                    json.parseToJsonElement(inputStr).jsonObject
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
                return ContentBlock.ToolUse(id = id, name = name, input = inputObj)
            }
        }

        abstract fun toContentBlock(json: Json): ContentBlock?
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 与 claude-code `DEFAULT_MAX_RETRIES` 同量级；移动端可略少。 */
        private const val DEFAULT_MAX_RETRIES = 8
        private const val BASE_DELAY_MS = 500L
        private const val MAX_BACKOFF_MS = 32_000L

        private fun shouldRetryAnthropic(e: AnthropicApiException): Boolean {
            if (e.statusCode in RETRY_HTTP_CODES) return true
            if (e.statusCode in 500..599) return true
            if (e.statusCode == 0 && isRetryableStreamErrorMessage(e.message)) return true
            return false
        }

        private val RETRY_HTTP_CODES = setOf(408, 409, 429)

        private fun isRetryableStreamErrorMessage(message: String): Boolean {
            val m = message.lowercase()
            return m.contains("overloaded") ||
                m.contains("overloaded_error") ||
                m.contains("rate_limit") ||
                m.contains("529") ||
                m.contains("too many requests")
        }

        /**
         * 对齐 [claude-code `getRetryDelay`](https://github.com/anthropics/claude-code/blob/main/src/services/api/withRetry.ts)：
         * 优先 `Retry-After`，否则指数退避 + 抖动。
         */
        internal fun computeRetryDelayMs(attempt: Int, retryAfterMs: Long?): Long {
            if (retryAfterMs != null && retryAfterMs > 0L) {
                return min(retryAfterMs, MAX_BACKOFF_MS * 4)
            }
            val base = min(BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(16)), MAX_BACKOFF_MS)
            val jitter = (Math.random() * 0.25 * base).toLong()
            return base + jitter
        }
    }
}
