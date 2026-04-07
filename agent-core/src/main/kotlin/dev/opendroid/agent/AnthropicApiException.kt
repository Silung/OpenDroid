package dev.opendroid.agent

/**
 * Anthropic Messages API 失败（HTTP 失败或 SSE `error` 事件）。
 * 与 claude-code 中 `APIError` + 结构化日志的思路对齐，便于区分状态码与 request_id。
 */
class AnthropicApiException(
    val statusCode: Int,
    override val message: String,
    val requestId: String? = null,
    /** 来自 HTTP `Retry-After`（秒）换算的毫秒，或 null。 */
    val retryAfterMillisHint: Long? = null,
    /** 非 2xx 时的 HTTP 响应体原文（截断前），便于 debug 落盘。 */
    val rawHttpBody: String? = null,
) : Exception(message)
