package dev.opendroid.device

/**
 * 连接中的无障碍实例。Manifest 仅声明一项（SelectToSpeak 组件名）；Holder 仍保留 primary / compat 双槽以便扩展。
 */
object OpenDroidAccessibilityHolder {

    @Volatile
    private var primary: OpenDroidAccessibilityService? = null

    @Volatile
    private var whitelistUiCompat: OpenDroidAccessibilityService? = null

    internal fun attach(svc: OpenDroidAccessibilityService, registration: OpenDroidAccessibilityRegistration) {
        when (registration) {
            OpenDroidAccessibilityRegistration.PRIMARY -> primary = svc
            OpenDroidAccessibilityRegistration.WHITELIST_UI_COMPAT -> whitelistUiCompat = svc
        }
    }

    internal fun detach(svc: OpenDroidAccessibilityService, registration: OpenDroidAccessibilityRegistration) {
        when (registration) {
            OpenDroidAccessibilityRegistration.PRIMARY -> if (primary === svc) primary = null
            OpenDroidAccessibilityRegistration.WHITELIST_UI_COMPAT -> if (whitelistUiCompat === svc) whitelistUiCompat = null
        }
    }

    /** 手势、截图、取包名等：优先主服务，否则兼容服务（用户只开一条时仍能工作）。 */
    fun serviceForGestures(): OpenDroidAccessibilityService? = primary ?: whitelistUiCompat

    fun serviceForTree(source: OpenDroidAccessibilityTreeSource): OpenDroidAccessibilityService? =
        when (source) {
            OpenDroidAccessibilityTreeSource.DEFAULT -> primary ?: whitelistUiCompat
            // 与 DEFAULT 相同实例；保留参数供 Agent / 历史请求兼容
            OpenDroidAccessibilityTreeSource.WHITELIST_COMPAT -> whitelistUiCompat ?: primary
        }
}
