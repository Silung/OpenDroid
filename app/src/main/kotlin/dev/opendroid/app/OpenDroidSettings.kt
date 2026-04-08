package dev.opendroid.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * LLM 请求使用的 HTTP API 形态（与 SiliconFlow / Anthropic 路径一致）。
 * - [OPENAI_CHAT_COMPLETIONS]：`/v1/chat/completions`，多模态用 `image_url`（默认，适合硅基流动文档）。
 * - [ANTHROPIC_MESSAGES]：`/v1/messages`，Anthropic Messages 格式。
 */
enum class LlmApiFormat(val prefValue: String) {
    OPENAI_CHAT_COMPLETIONS("openai"),
    ANTHROPIC_MESSAGES("anthropic"),
    ;

    companion object {
        fun fromPref(raw: String?): LlmApiFormat {
            val v = raw?.trim().orEmpty()
            return when (v) {
                ANTHROPIC_MESSAGES.prefValue -> ANTHROPIC_MESSAGES
                else -> OPENAI_CHAT_COMPLETIONS
            }
        }
    }
}

/**
 * 用户在设置中填写的内容存加密偏好；**留空**表示使用 [OpenDroidEndpointDefaults]（编译期来自 local.properties，见该类说明）。
 */
class OpenDroidSettings(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "opendroid_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** 实际用于请求的 Key：偏好非空优先，否则 [BuildConfig.DEFAULT_LLM_API_KEY]。 */
    var anthropicApiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty().ifBlank { BuildConfig.DEFAULT_LLM_API_KEY }
        set(value) = prefs.edit().putString(KEY_API, value).apply()

    var anthropicBaseUrl: String
        get() = prefs.getString(KEY_BASE, "").orEmpty().ifBlank { OpenDroidEndpointDefaults.llmBaseUrl }
        set(value) = prefs.edit().putString(KEY_BASE, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, "").orEmpty().ifBlank { OpenDroidEndpointDefaults.llmModel }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /**
     * OpenAI 兼容 `/v1/chat/completions` 与 Anthropic `/v1/messages`。
     * 设置里未保存过时回落 [OpenDroidEndpointDefaults.llmApiFormat]（`local.properties`：`opendroid.default.llm.api.format`）。
     */
    var llmApiFormat: LlmApiFormat
        get() {
            val raw = prefs.getString(KEY_API_FORMAT, "").orEmpty()
            if (raw.isNotBlank()) return LlmApiFormat.fromPref(raw)
            return OpenDroidEndpointDefaults.llmApiFormat
        }
        set(value) = prefs.edit().putString(KEY_API_FORMAT, value.prefValue).apply()

    fun llmApiFormatStoredRaw(): String = prefs.getString(KEY_API_FORMAT, "").orEmpty()

    /** 设置页展示用：仅已保存内容（不把 BuildConfig 默认写进输入框）。 */
    fun anthropicApiKeyStoredRaw(): String = prefs.getString(KEY_API, "").orEmpty()

    fun anthropicBaseUrlStoredRaw(): String = prefs.getString(KEY_BASE, "").orEmpty()

    fun modelStoredRaw(): String = prefs.getString(KEY_MODEL, "").orEmpty()

    var maxTurns: Int
        get() = prefs.getString(KEY_MAX_TURNS, "24")?.toIntOrNull() ?: 24
        set(value) = prefs.edit().putString(KEY_MAX_TURNS, value.coerceIn(1, 256).toString()).apply()

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY, value).apply()

    /**
     * 开启后，发往 LLM 的请求中 **移除** `tool_result` 附带的截图二进制（仅保留 JSON 正文，如 omniparser）。
     * 适用于不支持多模态的纯文本模型；会话与界面仍保存截图元数据，聊天缩略图逻辑不变。
     */
    var llmOmitToolResultImages: Boolean
        get() = prefs.getBoolean(KEY_LLM_OMIT_TOOL_IMAGES, false)
        set(value) = prefs.edit().putBoolean(KEY_LLM_OMIT_TOOL_IMAGES, value).apply()

    /** 当前聊天会话 id，位于 filesDir/chat_sessions/ */
    var currentChatSessionId: String
        get() = prefs.getString(KEY_CHAT_SESSION, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CHAT_SESSION, value).apply()

    /**
     * 是否对截图发起 OmniParser 请求并合并 `omniparser` 字段。关闭时仍保留 URL/Key 配置以便下次开启。
     */
    var omniparserEnabled: Boolean
        get() = prefs.getBoolean(KEY_OMNI_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_OMNI_ENABLED, value).apply()

    /**
     * OmniParser `POST /parse/` 完整 URL。偏好留空时回落 [OpenDroidEndpointDefaults.omniparserParseUrl]（未在 local.properties 配置则为空，即不请求）。
     */
    var omniparserParseUrl: String
        get() = prefs.getString(KEY_OMNI_PARSE_URL, "").orEmpty()
            .ifBlank { OpenDroidEndpointDefaults.omniparserParseUrl }
        set(value) = prefs.edit().putString(KEY_OMNI_PARSE_URL, value).apply()

    /** 可选；与服务端 `OMNIPARSER_API_KEY` / `--api-key` 对应。 */
    var omniparserApiKey: String
        get() = prefs.getString(KEY_OMNI_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_OMNI_API_KEY, value).apply()

    fun omniparserParseUrlStoredRaw(): String = prefs.getString(KEY_OMNI_PARSE_URL, "").orEmpty()

    fun omniparserApiKeyStoredRaw(): String = prefs.getString(KEY_OMNI_API_KEY, "").orEmpty()

    companion object {
        private const val KEY_API = "anthropic_api_key"
        private const val KEY_BASE = "anthropic_base_url"
        private const val KEY_MODEL = "anthropic_model"
        private const val KEY_API_FORMAT = "llm_api_format"
        private const val KEY_MAX_TURNS = "agent_max_turns"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_CHAT_SESSION = "current_chat_session_id"
        private const val KEY_OMNI_ENABLED = "omniparser_enabled"
        private const val KEY_OMNI_PARSE_URL = "omniparser_parse_url"
        private const val KEY_OMNI_API_KEY = "omniparser_api_key"
        private const val KEY_LLM_OMIT_TOOL_IMAGES = "llm_omit_tool_result_images"
    }
}
