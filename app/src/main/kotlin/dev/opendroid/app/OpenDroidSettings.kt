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
 * 用户在设置中填写的内容存加密偏好；**留空**表示使用 [BuildConfig] 默认值（由 local.properties 或 CI 环境变量注入）。
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
        get() = prefs.getString(KEY_BASE, "").orEmpty().ifBlank { BuildConfig.DEFAULT_LLM_BASE_URL }
        set(value) = prefs.edit().putString(KEY_BASE, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, "").orEmpty().ifBlank { BuildConfig.DEFAULT_LLM_MODEL }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** 默认 OpenAI 兼容（硅基流动多模态文档路径）。 */
    var llmApiFormat: LlmApiFormat
        get() = LlmApiFormat.fromPref(prefs.getString(KEY_API_FORMAT, null))
        set(value) = prefs.edit().putString(KEY_API_FORMAT, value.prefValue).apply()

    fun llmApiFormatStoredRaw(): String = prefs.getString(KEY_API_FORMAT, "").orEmpty()

    /** 设置页展示用：仅已保存内容（不把 BuildConfig 默认写进输入框）。 */
    fun anthropicApiKeyStoredRaw(): String = prefs.getString(KEY_API, "").orEmpty()

    fun anthropicBaseUrlStoredRaw(): String = prefs.getString(KEY_BASE, "").orEmpty()

    fun modelStoredRaw(): String = prefs.getString(KEY_MODEL, "").orEmpty()

    var maxTurns: Int
        get() = prefs.getString(KEY_MAX_TURNS, "24")?.toIntOrNull() ?: 24
        set(value) = prefs.edit().putString(KEY_MAX_TURNS, value.coerceIn(1, 256).toString()).apply()

    /**
     * 每轮请求 LLM 时，上下文中至多保留最近多少条 **Assistant**；User 不设此项上限。
     * 见 [dev.opendroid.agent.sliceChatMessagesForLlmRequest]。
     */
    var maxLlmHistoryAssistantMessages: Int
        get() = prefs.getString(KEY_MAX_LLM_HISTORY, "6")?.toIntOrNull()?.coerceIn(1, 256) ?: 6
        set(value) = prefs.edit().putString(KEY_MAX_LLM_HISTORY, value.coerceIn(1, 256).toString()).apply()

    fun maxLlmHistoryAssistantMessagesStoredRaw(): String = prefs.getString(KEY_MAX_LLM_HISTORY, "").orEmpty()

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY, value).apply()

    /** 当前聊天会话 id，位于 filesDir/chat_sessions/ */
    var currentChatSessionId: String
        get() = prefs.getString(KEY_CHAT_SESSION, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CHAT_SESSION, value).apply()

    companion object {
        private const val KEY_API = "anthropic_api_key"
        private const val KEY_BASE = "anthropic_base_url"
        private const val KEY_MODEL = "anthropic_model"
        private const val KEY_API_FORMAT = "llm_api_format"
        private const val KEY_MAX_TURNS = "agent_max_turns"
        private const val KEY_MAX_LLM_HISTORY = "max_llm_history_messages"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_CHAT_SESSION = "current_chat_session_id"
    }
}
