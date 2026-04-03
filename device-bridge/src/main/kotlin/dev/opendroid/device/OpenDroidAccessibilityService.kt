package dev.opendroid.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

open class OpenDroidAccessibilityService : AccessibilityService() {

    protected open val openDroidAccessibilityRegistration: OpenDroidAccessibilityRegistration =
        OpenDroidAccessibilityRegistration.PRIMARY

    final override fun onServiceConnected() {
        super.onServiceConnected()
        OpenDroidAccessibilityHolder.attach(this, openDroidAccessibilityRegistration)
    }

    final override fun onDestroy() {
        OpenDroidAccessibilityHolder.detach(this, openDroidAccessibilityRegistration)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events optional: tree is pulled on demand for Agent.
    }

    override fun onInterrupt() {
    }
}
