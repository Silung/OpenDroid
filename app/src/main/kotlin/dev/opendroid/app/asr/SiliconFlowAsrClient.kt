package dev.opendroid.app.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SiliconFlow [创建语音转文本](https://docs.siliconflow.cn/cn/api-reference/audio/create-audio-transcriptions)：单次 JSON 响应，无流式。
 */
object SiliconFlowAsrClient {

    const val DEFAULT_MODEL = "FunAudioLLM/SenseVoiceSmall"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** [baseUrl] 与 LLM 设置一致，例如 https://api.siliconflow.cn/ */
    fun transcriptionsUrl(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/v1")) "$b/audio/transcriptions" else "$b/v1/audio/transcriptions"
    }

    suspend fun transcribe(
        apiKey: String,
        baseUrl: String,
        audioFile: File,
        model: String = DEFAULT_MODEL,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "missing_api_key" }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody(guessAudioMediaType(audioFile.name).toMediaType()),
                )
                .build()
            val req = Request.Builder()
                .url(transcriptionsUrl(baseUrl))
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            http.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw AsrHttpException(res.code, raw)
                }
                val parsed = runCatching { json.decodeFromString<AsrResponse>(raw) }.getOrElse {
                    throw AsrHttpException(res.code, raw.ifBlank { "Empty body" })
                }
                parsed.text
            }
        }
    }

    private fun guessAudioMediaType(filename: String): String = when {
        filename.endsWith(".mp3", true) -> "audio/mpeg"
        filename.endsWith(".m4a", true) -> "audio/mp4"
        filename.endsWith(".aac", true) -> "audio/aac"
        filename.endsWith(".wav", true) -> "audio/wav"
        filename.endsWith(".ogg", true) -> "audio/ogg"
        filename.endsWith(".webm", true) -> "audio/webm"
        filename.endsWith(".amr", true) -> "audio/amr"
        else -> "application/octet-stream"
    }
}

@Serializable
internal data class AsrResponse(
    @SerialName("text") val text: String = "",
)

class AsrHttpException(val code: Int, val bodySnippet: String) :
    Exception("HTTP $code: ${bodySnippet.take(500)}")
