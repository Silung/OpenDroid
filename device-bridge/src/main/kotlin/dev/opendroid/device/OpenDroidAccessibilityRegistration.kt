package dev.opendroid.device

/**
 * 无障碍服务在 [OpenDroidAccessibilityHolder] 中的登记类别。
 * 当前仅注册 `SelectToSpeakService`，使用 [PRIMARY]。
 */
enum class OpenDroidAccessibilityRegistration {
    /** 当前唯一入口：`com.google.android.accessibility.selecttospeak.SelectToSpeakService` */
    PRIMARY,

    /** 保留：历史上用于第二条「白名单兼容」实例；现与 [PRIMARY] 可并存于 Holder 逻辑中。 */
    WHITELIST_UI_COMPAT,
}

enum class OpenDroidAccessibilityTreeSource {
    /** 默认读树 */
    DEFAULT,

    /**
     * 与 [DEFAULT] 指向同一无障碍连接；保留键名供 Agent 在失败后切换重试。
     */
    WHITELIST_COMPAT,
}
