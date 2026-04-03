package dev.opendroid.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography

/**
 * 聊天气泡等可组合项复用的颜色（不全部塞进 ColorScheme，避免牵一发动全身）。
 */
object OpenDroidChatPalette {
    /** 参考常见聊天 App：浅灰气泡 + 深字，避免「白底蓝字」偏工具感 */
    val userBubbleBackground: Color = Color(0xFFE9EDF2)
    val userBubbleText: Color = Color(0xFF1C1C1E)
    val userBubbleOutline: Color = Color(0xFFD7DDE4)
    val agentBubbleBackground: Color = Color(0xFFF8F9FB)
    val agentBubbleOutline: Color = Color(0xFFE6E9EF)
    val agentText: Color = Color(0xFF1C1C1E)
    val chatAreaBackground: Color = Color(0xFFF0F2F5)
}

private val OpenDroidColors = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    secondary = Color(0xFF5C6BC0),
    background = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF0F4),
    onBackground = Color(0xFF1C1C1E),
    onSurface = Color(0xFF1C1C1E),
)

/** 正文用系统默认无衬线；全文等宽会显得像终端而非消费级聊天。代码块仍在局部使用 Monospace。 */
private val OpenDroidTypography = Typography()

@Composable
fun OpenDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OpenDroidColors,
        typography = OpenDroidTypography,
        content = content,
    )
}
