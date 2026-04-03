package dev.opendroid.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

suspend fun AccessibilityService.dispatchGestureSuspend(stroke: GestureDescription.StrokeDescription): Boolean =
    suspendCoroutine { cont ->
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    cont.resume(false)
                }
            },
            null,
        )
    }

suspend fun AccessibilityService.tapAt(px: Float, py: Float, durationMs: Long = 80L): Boolean {
    val p = Path()
    p.moveTo(px, py)
    val stroke = GestureDescription.StrokeDescription(p, 0, durationMs.coerceAtLeast(1L))
    return dispatchGestureSuspend(stroke)
}

suspend fun AccessibilityService.swipeLine(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    durationMs: Long = 300L,
): Boolean {
    val p = Path()
    p.moveTo(x1, y1)
    p.lineTo(x2, y2)
    val stroke = GestureDescription.StrokeDescription(p, 0, durationMs.coerceAtLeast(1L))
    return dispatchGestureSuspend(stroke)
}

suspend fun AccessibilityService.dragPoints(
    points: List<Pair<Float, Float>>,
    durationMs: Long,
): Boolean {
    if (points.size < 2) return false
    val p = Path()
    p.moveTo(points[0].first, points[0].second)
    for (i in 1 until points.size) {
        p.lineTo(points[i].first, points[i].second)
    }
    val stroke = GestureDescription.StrokeDescription(p, 0, durationMs.coerceAtLeast(1L))
    return dispatchGestureSuspend(stroke)
}
