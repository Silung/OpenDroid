package dev.opendroid.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    /** 当前聊天会话 id，位于 filesDir/chat_sessions/ */
    var currentChatSessionId: String
        get() = prefs.getString(KEY_CHAT_SESSION, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CHAT_SESSION, value).apply()

    companion object {
        private const val KEY_API = "anthropic_api_key"
        private const val KEY_BASE = "anthropic_base_url"
        private const val KEY_MODEL = "anthropic_model"
        private const val KEY_MAX_TURNS = "agent_max_turns"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_CHAT_SESSION = "current_chat_session_id"
    }
}
