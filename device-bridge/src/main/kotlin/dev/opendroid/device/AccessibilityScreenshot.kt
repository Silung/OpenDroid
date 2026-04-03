package dev.opendroid.device

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import androidx.annotation.RequiresApi
import dev.opendroid.agent.ToolExecutionResult
import dev.opendroid.agent.ToolResultImage
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@RequiresApi(Build.VERSION_CODES.S)
suspend fun OpenDroidAccessibilityService.captureScreenshotForAgent(
    maxLongEdge: Int,
    maxShortEdge: Int,
    jpegQuality: Int,
): ToolExecutionResult = suspendCancellableCoroutine { cont ->
    takeScreenshot(
        Display.DEFAULT_DISPLAY,
        mainExecutor,
        object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                val hb = screenshot.hardwareBuffer
                try {
                    val wrapped = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                    if (wrapped == null) {
                        cont.resume(ToolExecutionResult("{\"error\":\"wrap_hardware_buffer_failed\"}", isError = true))
                        return
                    }
                    val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped.recycle()
                    val scaled = scaleBitmapToMaxEdgeBounds(software, maxLongEdge, maxShortEdge)
                    if (scaled !== software) {
                        software.recycle()
                    }
                    val stream = ByteArrayOutputStream()
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)) {
                        scaled.recycle()
                        cont.resume(ToolExecutionResult("{\"error\":\"jpeg_compress_failed\"}", isError = true))
                        return
                    }
                    val w = scaled.width
                    val h = scaled.height
                    scaled.recycle()
                    val bytes = stream.toByteArray()
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val metaJson = buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("media_type", JsonPrimitive("image/jpeg"))
                        put("width", JsonPrimitive(w))
                        put("height", JsonPrimitive(h))
                        put("jpegByteLength", JsonPrimitive(bytes.size))
                        put(
                            "note",
                            JsonPrimitive(
                                "Metadata only. The JPEG pixels are attached to this tool_result as separate image content (vision), not inside this JSON string.",
                            ),
                        )
                    }
                    val text = ScreenshotMetaJson.encode.encodeToString(JsonObject.serializer(), metaJson)
                    cont.resume(
                        ToolExecutionResult(
                            text = text,
                            images = listOf(ToolResultImage("image/jpeg", b64)),
                        ),
                    )
                } catch (e: Exception) {
                    cont.resume(
                        ToolExecutionResult(
                            "{\"error\":\"screenshot_processing\",\"detail\":\"${e.message?.replace("\"", "'")}\"}",
                            isError = true,
                        ),
                    )
                } finally {
                    runCatching { hb.close() }
                }
            }

            override fun onFailure(errorCode: Int) {
                val hint = screenshotFailureHint(errorCode)
                val errObj = buildJsonObject {
                    put("error", JsonPrimitive("take_screenshot_failed"))
                    put("code", JsonPrimitive(errorCode))
                    put("hint", JsonPrimitive(hint))
                }
                cont.resume(
                    ToolExecutionResult(
                        ScreenshotMetaJson.encode.encodeToString(JsonObject.serializer(), errObj),
                        isError = true,
                    ),
                )
            }
        },
    )
}

/**
 * 缩放到不超过长边/短边上限：长边 ≤ [maxLong]、短边 ≤ [maxShort]（工具默认 854×480，约 480p 16:9），等比、不放大。
 */
private fun scaleBitmapToMaxEdgeBounds(src: Bitmap, maxLong: Int, maxShort: Int): Bitmap {
    val w = src.width.toFloat()
    val h = src.height.toFloat()
    val longE = maxOf(w, h)
    val shortE = minOf(w, h)
    if (longE <= 0f || shortE <= 0f) return src
    val scale = minOf(maxLong / longE, maxShort / shortE).coerceAtMost(1f)
    if (scale >= 1f) {
        return src
    }
    val nw = (w * scale).toInt().coerceAtLeast(1)
    val nh = (h * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, nw, nh, true)
}

private object ScreenshotMetaJson {
    val encode = Json { prettyPrint = false }
}

/**
 * [AccessibilityService.TakeScreenshotCallback] error codes (API 31+).
 */
private fun screenshotFailureHint(code: Int): String = when (code) {
    1 -> // ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
        "Internal screenshot error — retry once; if it persists, reboot or check display recording policy."
    2 -> // ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
        "Turn ON the accessibility service’s **full-screen / screenshot** permission (Settings → Accessibility → your service → allow screenshot)."
    3 -> // ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY
        "Invalid display — use default display only on this device."
    4 -> // ERROR_TAKE_SCREENSHOT_INCLUDE_NOT_VISIBLE
        "Screenshot included non-visible regions — internal; retry {}."
    5 -> // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        "Called too soon after last screenshot — wait ~500ms and call capture_screenshot again."
    6 -> // ERROR_TAKE_SCREENSHOT_INVALID_WINDOW
        "No valid window — wait for UI to settle, then retry."
    7 -> // ERROR_TAKE_SCREENSHOT_SKIP_SYSTEM_WINDOW
        "Skipping system window — try again when the target app is in the foreground."
    else ->
        "Android takeScreenshot failed (code=$code). Ensure Android 12+, accessibility running, " +
            "and **screenshot allowed** for OpenDroid; then retry with {}."
}
