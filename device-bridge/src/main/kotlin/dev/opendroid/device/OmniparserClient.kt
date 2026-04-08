package dev.opendroid.device

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OmniParser FastAPI `POST /parse/` 配置。
 * [parseUrl] 留空表示不调用（仅返回截图元数据 + 图片）。
 */
data class OmniparserParseConfig(
    val parseUrl: String,
    val apiKey: String? = null,
    val timeoutMs: Int = 120_000,
) {
    fun enabled(): Boolean = parseUrl.isNotBlank()

    companion object {
        val Disabled = OmniparserParseConfig(parseUrl = "")
    }
}

private val omniparserJson = Json { ignoreUnknownKeys = true }

/**
 * 将 JPEG 的 Base64（不含 data: 前缀）POST 到 OmniParser，返回写入工具 JSON 的 `omniparser` 对象。
 * 成功时含 `ok`、`parsed_content_list`、`latency`；**不包含** `som_image_base64` 以控制体积。
 */
internal fun postOmniparserParse(
    config: OmniparserParseConfig,
    base64Jpeg: String,
): JsonObject {
    val url = URL(config.parseUrl.trim())
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val key = config.apiKey?.trim().orEmpty()
        if (key.isNotEmpty()) {
            setRequestProperty("Authorization", "Bearer $key")
        }
        doOutput = true
        connectTimeout = config.timeoutMs
        readTimeout = config.timeoutMs
    }
    val payload = buildJsonObject {
        put("base64_image", JsonPrimitive(base64Jpeg))
    }
    val bodyBytes = omniparserJson.encodeToString(JsonObject.serializer(), payload).toByteArray(Charsets.UTF_8)
    conn.outputStream.use { it.write(bodyBytes) }

    val code = conn.responseCode
    val stream = (if (code in 200..299) conn.inputStream else conn.errorStream) ?: conn.inputStream
    val respText = stream.use { it.readBytes().toString(Charsets.UTF_8) }
    conn.disconnect()

    if (code !in 200..299) {
        return buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("httpStatus", JsonPrimitive(code))
            put("detail", JsonPrimitive(respText.take(2000)))
        }
    }

    val root = try {
        omniparserJson.decodeFromString(JsonObject.serializer(), respText)
    } catch (_: Exception) {
        return buildJsonObject {
            put("ok", JsonPrimitive(false))
            put("error", JsonPrimitive("invalid_json_response"))
            put("detail", JsonPrimitive(respText.take(1000)))
        }
    }

    return buildJsonObject {
        put("ok", JsonPrimitive(true))
        root["parsed_content_list"]?.let { put("parsed_content_list", it) }
        root["latency"]?.let { put("latency", it) }
        val som = root["som_image_base64"]?.jsonPrimitive?.content
        if (som != null) {
            put("som_image_omitted", JsonPrimitive(true))
            put("som_image_base64_length", JsonPrimitive(som.length))
        }
    }
}
