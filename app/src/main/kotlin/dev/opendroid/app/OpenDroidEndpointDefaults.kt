package dev.opendroid.app

/**
 * 可调远程端点**编译期默认值**（设置页留空时使用用户设备上加密偏好中的空串，则由这里回落）。
 *
 * 修改方式：编辑项目根目录 **local.properties**（勿提交仓库），键名如下；Gradle 注入 BuildConfig。
 *
 * - [llmBaseUrl] ← `opendroid.default.llm.base.url`
 * - [llmModel] ← `opendroid.default.llm.model`
 * - [llmApiFormat] ← `opendroid.default.llm.api.format`（`openai` 或 `anthropic`；缺省为 openai）
 * - [omniparserParseUrl] ← `opendroid.default.omniparser.parse.url`
 *
 * API Key 仍用 `opendroid.default.api.key` / 环境变量 `OPENDROID_DEFAULT_API_KEY`。
 */
object OpenDroidEndpointDefaults {
    val llmBaseUrl: String get() = BuildConfig.DEFAULT_LLM_BASE_URL

    val llmModel: String get() = BuildConfig.DEFAULT_LLM_MODEL

    /** 与设置里「API 格式」一致；来自 `opendroid.default.llm.api.format`。 */
    val llmApiFormat: LlmApiFormat
        get() = LlmApiFormat.fromPref(BuildConfig.DEFAULT_LLM_API_FORMAT)

    /** OmniParser `POST /parse/`；可为空字符串。 */
    val omniparserParseUrl: String get() = BuildConfig.DEFAULT_OMNIPARSER_PARSE_URL
}
