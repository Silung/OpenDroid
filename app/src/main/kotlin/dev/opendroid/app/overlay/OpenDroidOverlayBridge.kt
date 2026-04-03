package dev.opendroid.app.overlay

import android.content.Context
import dev.opendroid.app.ChatLine
import dev.opendroid.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OverlayDisplayState(
    val body: String,
    val status: String?,
)

/**
 * 将聊天区状态同步到 [FloatingOverlayService]（同进程 StateFlow）。
 * 文案使用 [Context] 读取字符串资源，以随应用内语言切换变化。
 */
object OpenDroidOverlayBridge {
    private val _displayState = MutableStateFlow(OverlayDisplayState("", null))
    val displayState: StateFlow<OverlayDisplayState> = _displayState.asStateFlow()

    private var lastLines: List<ChatLine> = emptyList()
    private var lastBusy: Boolean = false

    /** 进程启动时填充本地化空白占位（见 [OpenDroidApp.onCreate]）。 */
    fun setEmptyState(context: Context) {
        lastLines = emptyList()
        lastBusy = false
        _displayState.value = emptyState(context.applicationContext)
    }

    fun updateFromChat(context: Context, lines: List<ChatLine>, busy: Boolean) {
        lastLines = lines
        lastBusy = busy
        _displayState.value = computeFrom(context.applicationContext, lines, busy)
    }

    fun clear(context: Context) {
        lastLines = emptyList()
        lastBusy = false
        _displayState.value = emptyState(context.applicationContext)
    }

    /**
     * 应用内切换语言后 Activity 会重建，但 ViewModel 可能不再次推流；
     * 在 [MainActivity.onCreate] 调用以使悬浮窗等立刻使用新 locale。
     */
    fun refreshWithCurrentLocale(context: Context) {
        val appCtx = context.applicationContext
        _displayState.value = if (lastLines.isEmpty() && !lastBusy) {
            emptyState(appCtx)
        } else {
            computeFrom(appCtx, lastLines, lastBusy)
        }
    }

    private fun emptyState(appCtx: Context) = OverlayDisplayState(
        appCtx.getString(R.string.overlay_body_empty_idle),
        null,
    )

    private fun computeFrom(appCtx: Context, lines: List<ChatLine>, busy: Boolean): OverlayDisplayState {
        val last = lines.lastOrNull()
        val lastAssistant = lines.findLast { it is ChatLine.Assistant } as? ChatLine.Assistant
        val bodyRaw = buildString {
            if (lastAssistant != null) {
                append(lastAssistant.text.trim())
                if (lastAssistant.streaming) append(" …")
            } else {
                append(appCtx.getString(R.string.overlay_body_no_reply))
            }
        }
        val body = if (bodyRaw.length > 420) bodyRaw.take(420) + "…" else bodyRaw

        val status = when {
            !busy -> {
                when (last) {
                    is ChatLine.System ->
                        "⚠ ${last.text.take(160)}${if (last.text.length > 160) "…" else ""}"
                    else -> null
                }
            }
            last is ChatLine.Tool -> appCtx.getString(R.string.overlay_status_tool, last.name)
            last is ChatLine.Assistant ->
                if (last.streaming) {
                    appCtx.getString(R.string.overlay_status_streaming)
                } else {
                    appCtx.getString(R.string.overlay_status_processing)
                }
            last is ChatLine.User -> appCtx.getString(R.string.overlay_status_waiting)
            else -> appCtx.getString(R.string.overlay_status_ellipsis)
        }
        return OverlayDisplayState(body = body, status = status)
    }
}
