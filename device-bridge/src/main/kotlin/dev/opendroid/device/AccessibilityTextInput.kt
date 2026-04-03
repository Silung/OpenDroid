package dev.opendroid.device

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Uses [AccessibilityNodeInfo.ACTION_SET_TEXT] on the current IME focus.
 * Caller should tap the field first so it is focused; WebView / some OEM fields may still fail.
 */
fun AccessibilityService.inputTextToFocused(text: String, append: Boolean): String {
    val root = rootInActiveWindow ?: return "no_active_window"
    val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    root.recycle()
    if (focus == null) return "no_focused_editable"
    try {
        if (!focus.isEditable) return "focused_node_not_editable"
        val finalText =
            if (append) {
                focus.text?.toString().orEmpty() + text
            } else {
                text
            }
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        return if (focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
            "ok"
        } else {
            "set_text_action_failed"
        }
    } finally {
        focus.recycle()
    }
}
