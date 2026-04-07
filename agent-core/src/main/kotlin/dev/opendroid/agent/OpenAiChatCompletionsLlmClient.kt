package dev.opendroid.agent

import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/**
 * OpenAI Chat Completions 兼容客户端（流式），用于 SiliconFlow ` /v1/chat/completions` 等。
 * 多模态 `capture_screenshot` 通过 [openAiExpandUserMessage] 转为 `image_url` + `tool` 消息。
 */
class OpenAiChatCompletionsLlmClient(
    private val apiKey: String,
    baseUrl: String,
    client: OkHttpClient? = null,
    maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val trafficLogger: LlmTurnTrafficLogger? = null,
) : LlmClient {

    private val endpointUrl = openAiChatCompletionsEndpoint(baseUrl)
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
        val bodyJson = json.encodeToString(
            JsonObject.serializer(),
            buildOpenAiChatCompletionRequestBody(request, json),
        )
        var lastError: Exception? = null
        repeat(maxRetries) { attemptIndex ->
            val httpReq = buildHttpRequest(bodyJson)
            try {
                val (result, sseRaw) = executeStreamingTurn(httpReq, onTextDelta)
                trafficLogger?.onLlmExchange(attemptIndex, bodyJson, sseRaw, result, null)
                return@withContext Result.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenAiApiException) {
                lastError = e
                trafficLogger?.onLlmExchange(attemptIndex, bodyJson, e.rawHttpBody.orEmpty(), null, e)
                val isLast = attemptIndex >= maxRetries - 1
                if (!shouldRetryOpenAi(e) || isLast) {
                    return@withContext Result.failure(e)
                }
                coroutineContext.ensureActive()
                delay(AnthropicLlmClient.computeRetryDelayMs(attemptIndex + 1, e.retryAfterMillisHint))
            } catch (e: IOException) {
                lastError = e
                trafficLogger?.onLlmExchange(attemptIndex, bodyJson, "", null, e)
                if (attemptIndex >= maxRetries - 1) {
                    return@withContext Result.failure(e)
                }
                coroutineContext.ensureActive()
                delay(AnthropicLlmClient.computeRetryDelayMs(attemptIndex + 1, null))
            } catch (e: Exception) {
                trafficLogger?.onLlmExchange(attemptIndex, bodyJson, "", null, e)
                coroutineContext.ensureActive()
                return@withContext Result.failure(e)
            }
        }
        Result.failure(lastError ?: IllegalStateException("OpenAI compat: exhausted retries"))
    }

    private fun shouldRetryOpenAi(e: OpenAiApiException): Boolean {
        if (e.statusCode in RETRY_HTTP_CODES) return true
        if (e.statusCode in 500..599) return true
        if (e.statusCode == 0) return true
        val msg = e.message.lowercase()
        if (msg.contains("rate") && msg.contains("limit")) return true
        if (msg.contains("<html") || e.rawHttpBody?.contains("<html", ignoreCase = true) == true) return true
        return false
    }

    private fun buildHttpRequest(bodyJson: String): Request =
        Request.Builder()
            .url(endpointUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("content-type", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()

    private suspend fun executeStreamingTurn(
        httpReq: Request,
        onTextDelta: suspend (String) -> Unit,
    ): Pair<AssistantTurnResult, String> {
        val call = http.newCall(httpReq)
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
                val retryHintMs = retryAfterSec?.times(1000)
                throw OpenAiApiException(
                    statusCode = response.code,
                    message = "OpenAI compat HTTP ${response.code}: ${errBody.take(800)}",
                    retryAfterMillisHint = retryHintMs,
                    rawHttpBody = errBody.take(MAX_DEBUG_HTTP_BODY_CHARS),
                )
            }
            val body = response.body
                ?: throw OpenAiApiException(0, "OpenAI compat: empty body", null, null)
            val lines = body.byteStream().bufferedReader().use { it.readLines() }
            val sseRaw = lines.joinToString("\n") + "\n"
            val result = parseOpenAiSseLines(lines.asSequence(), onTextDelta)
            return result to sseRaw
        }
    }

    private suspend fun parseOpenAiSseLines(
        lines: Sequence<String>,
        onTextDelta: suspend (String) -> Unit,
    ): AssistantTurnResult {
        val textAcc = StringBuilder()
        val uiSb = StringBuilder()
        val toolAccs = TreeMap<Int, ToolCallStreamAcc>()
        var stopReason: String? = null
        var lineTick = 0

        for (line in lines) {
            if (lineTick++ % 32 == 0) coroutineContext.ensureActive()
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]" || payload.isEmpty()) continue
            val root = try {
                json.parseToJsonElement(payload).jsonObject
            } catch (_: Exception) {
                continue
            }
            root["error"]?.jsonObject?.let { err ->
                val msg = err["message"]?.jsonPrimitive?.content ?: payload
                throw OpenAiApiException(0, "OpenAI compat stream error: $msg", null, payload.take(20_000))
            }
            val choices = root["choices"]?.jsonArray ?: continue
            val choice = choices.firstOrNull()?.jsonObject ?: continue
            choice["finish_reason"]?.jsonPrimitive?.content?.let { stopReason = it }
            val delta = choice["delta"]?.jsonObject ?: continue
            // content 常为 JSON null 或缺省，不能用 jsonPrimitive 强转，否则会异常或误拼接字面值 "null"
            val textPiece = openAiStreamDeltaTextPiece(delta)
            if (textPiece.isNotEmpty()) {
                textAcc.append(textPiece)
                uiSb.append(textPiece)
                onTextDelta(textPiece)
            }
            delta["tool_calls"]?.jsonArray?.forEach { el ->
                val o = el.jsonObject
                val idx = o["index"]?.jsonPrimitive?.intOrNull ?: 0
                val acc = toolAccs.getOrPut(idx) { ToolCallStreamAcc() }
                openAiOptJsonString(o, "id")?.let { acc.id = it }
                openAiOptJsonString(o, "type")?.let { acc.type = it }
                val fn = o["function"]?.jsonObject
                if (fn != null) {
                    openAiOptJsonString(fn, "name")?.let { acc.name = it }
                    openAiOptJsonString(fn, "arguments")?.let { acc.arguments.append(it) }
                }
            }
        }

        val blocks = mutableListOf<ContentBlock>()
        if (textAcc.isNotEmpty()) {
            blocks.add(ContentBlock.Text(textAcc.toString().trim()))
        }
        for ((_, acc) in toolAccs) {
            if (acc.name.isEmpty()) continue
            val argStr = acc.arguments.toString().trim().ifEmpty { "{}" }
            val inputObj = try {
                json.parseToJsonElement(argStr).jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
            val id = acc.id.ifEmpty { "call_openai_${acc.name.hashCode()}" }
            blocks.add(ContentBlock.ToolUse(id = id, name = acc.name, input = inputObj))
        }

        return AssistantTurnResult(
            blocks = blocks,
            stopReason = stopReason,
            rawTextForUi = uiSb.toString(),
        )
    }

    private class ToolCallStreamAcc {
        var id: String = ""
        var type: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_DEBUG_HTTP_BODY_CHARS = 512_000
        private const val DEFAULT_MAX_RETRIES = 8
        private val RETRY_HTTP_CODES = setOf(408, 429)
    }
}

class OpenAiApiException(
    val statusCode: Int,
    override val message: String,
    val retryAfterMillisHint: Long?,
    val rawHttpBody: String?,
) : Exception(message)

/** 合并流式 delta 中的正文：仅字符串 `content` / `text`，或 `content` 为数组时的 text 块。忽略 JSON null。 */
internal fun openAiStreamDeltaTextPiece(delta: JsonObject): String {
    openAiOptJsonContentElement(delta["content"])?.let { return it }
    openAiOptJsonContentElement(delta["text"])?.let { return it }
    return ""
}

internal fun openAiOptJsonContentElement(el: JsonElement?): String? {
    if (el == null || el is JsonNull) return null
    return when (el) {
        is JsonPrimitive -> {
            if (!el.isString) return null
            val s = el.content
            if (s == "null") return null
            s
        }
        is JsonArray -> openAiFlattenOpenAiContentArray(el)
        else -> null
    }
}

private fun openAiFlattenOpenAiContentArray(arr: JsonArray): String? {
    val sb = StringBuilder()
    for (item in arr) {
        val o = item as? JsonObject ?: continue
        val typ = o["type"]?.jsonPrimitive?.content ?: continue
        if (typ != "text") continue
        openAiOptJsonString(o, "text")?.let { sb.append(it) }
    }
    return sb.toString().takeIf { it.isNotEmpty() }
}

private fun openAiOptJsonString(o: JsonObject, key: String): String? {
    val el = o[key] ?: return null
    if (el is JsonNull) return null
    val p = el as? JsonPrimitive ?: return null
    if (!p.isString) return null
    val s = p.content
    return if (s == "null") null else s
}
