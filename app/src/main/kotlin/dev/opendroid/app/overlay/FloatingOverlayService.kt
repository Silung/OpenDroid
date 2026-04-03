package dev.opendroid.app.overlay

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.opendroid.app.OpenDroidSettings
import dev.opendroid.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var statusView: TextView? = null
    private var bodyView: TextView? = null

    private var lastRenderedBody: String? = null
    private var lastRenderedStatus: String? = null
    private var lastFlashElapsed = 0L
    private var contentFlashAnim: AnimatorSet? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this) || !OpenDroidSettings(this).overlayEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val (root, status, body) = buildOverlayViews()
        rootView = root
        statusView = status
        bodyView = body
        windowManager?.addView(root, buildLayoutParams())
        serviceScope.launch {
            OpenDroidOverlayBridge.displayState.collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!OpenDroidSettings(this).overlayEnabled || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        contentFlashAnim?.cancel()
        contentFlashAnim = null
        serviceScope.cancel()
        rootView?.let { v ->
            runCatching { windowManager?.removeViewImmediate(v) }
        }
        rootView = null
        statusView = null
        bodyView = null
        windowManager = null
        super.onDestroy()
    }

    private fun render(state: OverlayDisplayState) {
        val prevBody = lastRenderedBody
        val prevStatus = lastRenderedStatus
        val st = state.status
        val statusVisible = !st.isNullOrBlank()

        bodyView?.text = state.body
        if (!statusVisible) {
            statusView?.text = ""
            statusView?.visibility = View.GONE
        } else {
            statusView?.text = st
            statusView?.visibility = View.VISIBLE
        }

        val bodyChanged = state.body != prevBody
        val statusChanged = (st ?: "") != (prevStatus ?: "")
        lastRenderedBody = state.body
        lastRenderedStatus = st

        if (bodyChanged || statusChanged) {
            if (prevBody != null || prevStatus != null) {
                playContentFlash()
            }
        }
    }

    /** 文字更新时短暂高亮，流式输出时节流避免连闪。 */
    private fun playContentFlash() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlashElapsed < FLASH_THROTTLE_MS) return
        lastFlashElapsed = now

        val root = rootView ?: return
        val body = bodyView ?: return
        contentFlashAnim?.cancel()

        val durDim = 55L
        val durBright = 130L
        val aRoot = ObjectAnimator.ofFloat(root, View.ALPHA, 1f, 0.88f, 1f).apply {
            duration = durDim + durBright
        }
        val aBody = ObjectAnimator.ofArgb(
            body,
            "textColor",
            BODY_COLOR,
            BODY_FLASH_PEAK,
            BODY_COLOR,
        ).apply {
            duration = durDim + durBright
        }
        val animators = ArrayList<Animator>(3).apply {
            add(aRoot)
            add(aBody)
        }
        statusView?.takeIf { it.visibility == View.VISIBLE }?.let { s ->
            animators.add(
                ObjectAnimator.ofArgb(s, "textColor", STATUS_COLOR, STATUS_FLASH_PEAK, STATUS_COLOR).apply {
                    duration = durDim + durBright
                },
            )
        }
        contentFlashAnim = AnimatorSet().apply {
            playTogether(*animators.toTypedArray())
            start()
        }
    }

    private fun buildOverlayViews(): Triple<LinearLayout, TextView, TextView> {
        val density = resources.displayMetrics.density
        val pad = (12 * density).roundToInt()
        val maxW = (300 * density).roundToInt()
        val cornerPx = 14f * density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(PANEL_FILL_COLOR)
                val sw = (1 * density).roundToInt().coerceAtLeast(1)
                setStroke(sw, PANEL_STROKE_COLOR)
            }
            elevation = (5f * density).coerceIn(4f, 12f)
        }

        val title = TextView(this).apply {
            text = getString(R.string.overlay_window_title)
            textSize = 11f
            setTextColor(TITLE_COLOR)
            typeface = Typeface.DEFAULT_BOLD
            maxWidth = maxW
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = 0.02f
            setShadowLayer(2f, 0f, 1f, SHADOW)
        }
        val status = TextView(this).apply {
            textSize = 10f
            setTextColor(STATUS_COLOR)
            maxWidth = maxW
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setShadowLayer(1.5f, 0f, 1f, SHADOW)
        }
        val body = TextView(this).apply {
            textSize = 12f
            setTextColor(BODY_COLOR)
            maxWidth = maxW
            maxLines = 14
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.SANS_SERIF
            setShadowLayer(2f, 0f, 1f, SHADOW)
            setLineSpacing(3f * density, 1f)
        }

        val titleLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = (6 * density).roundToInt() }
        val statusLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = (4 * density).roundToInt() }
        val bodyLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        root.addView(title, titleLp)
        root.addView(status, statusLp)
        root.addView(body, bodyLp)
        return Triple(root, status, body)
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = (
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        val density = resources.displayMetrics.density
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (52 * density).roundToInt()
        }
    }

    private fun startAsForeground() {
        val channelId = getString(R.string.overlay_notification_channel_id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                channelId,
                getString(R.string.overlay_notification_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_opendroid)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val FLASH_THROTTLE_MS = 160L

        /** 偏冷灰半透明，圆角卡片底 */
        private val PANEL_FILL_COLOR = 0xE6282B33.toInt()
        private val PANEL_STROKE_COLOR = 0x55FFFFFF
        private val TITLE_COLOR = 0xF0FFFFFF.toInt()
        private val STATUS_COLOR = 0xE6FFE082.toInt()
        private val BODY_COLOR = 0xF5FFFFFF.toInt()
        private val BODY_FLASH_PEAK = 0xFFFFFFFF.toInt()
        private val STATUS_FLASH_PEAK = 0xFFFFF59D.toInt()
        private val SHADOW = 0xCC000000.toInt()

        fun startIfNeeded(context: Context) {
            val app = context.applicationContext
            if (!OpenDroidSettings(app).overlayEnabled) return
            if (!Settings.canDrawOverlays(app)) return
            val i = Intent(app, FloatingOverlayService::class.java)
            ContextCompat.startForegroundService(app, i)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, FloatingOverlayService::class.java))
        }
    }
}
