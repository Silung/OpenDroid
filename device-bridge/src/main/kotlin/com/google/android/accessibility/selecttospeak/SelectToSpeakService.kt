package com.google.android.accessibility.selecttospeak

import dev.opendroid.device.OpenDroidAccessibilityRegistration
import dev.opendroid.device.OpenDroidAccessibilityService

/**
 * OpenDroid **唯一**无障碍入口：组件全名与 Android 内置「随选朗读」一致
 *（`com.google.android.accessibility.selecttospeak.SelectToSpeakService`），
 * 便于厂商 / 应用对白名单后缀放行时仍可进行节点树采集与手势。
 *
 * 注意：若目标应用改为校验包名或签名，本方式可能失效。
 */
@Suppress("unused")
class SelectToSpeakService : OpenDroidAccessibilityService() {
    override val openDroidAccessibilityRegistration: OpenDroidAccessibilityRegistration =
        OpenDroidAccessibilityRegistration.PRIMARY
}
