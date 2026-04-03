package dev.opendroid.device

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObjectBuilder

private const val DEFAULT_MAX_NODES = 600

/**
 * @param compact When true, drops non-visible nodes, omits empty fields and false booleans, strips
 * boring containers (no text/desc/id, not interactable, no kept children). Shrinks LLM payload.
 * @param maxNodes Hard cap on nodes **visited** while walking (prevents huge traversals).
 * @param packageNameFilter When non-null/non-blank, only windows whose **root** [AccessibilityNodeInfo]
 *   packageName equals this string (exact match) are serialized—drops status bar, IME, and other apps.
 */
fun OpenDroidAccessibilityService.captureUiForestJson(
    maxDepth: Int = 12,
    compact: Boolean = true,
    maxNodes: Int = DEFAULT_MAX_NODES,
    hideNonVisible: Boolean = true,
    packageNameFilter: String? = null,
): JsonObject {
    val pkgFilter = packageNameFilter?.trim().orEmpty()
    val visited = IntNodeCounter(maxNodes)
    val windowsJson = buildJsonArray {
        var anyWindow = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val winList = windows
            if (winList != null) {
                for (w in winList) {
                    if (visited.exhausted()) break
                    val root = w.root ?: continue
                    try {
                        if (pkgFilter.isNotEmpty() &&
                            root.packageName?.toString().orEmpty() != pkgFilter
                        ) {
                            continue
                        }
                        val rootJson = nodeToJson(
                            node = root,
                            depth = 0,
                            maxDepth = maxDepth,
                            compact = compact,
                            hideNonVisible = hideNonVisible,
                            visited = visited,
                        ) ?: continue
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("window"))
                                put("title", JsonPrimitive(w.title?.toString().orEmpty()))
                                put("typeWindow", JsonPrimitive(windowTypeLabel(w.type)))
                                put("root", rootJson)
                            },
                        )
                        anyWindow = true
                    } finally {
                        root.recycle()
                    }
                }
            }
        }
        if (!anyWindow) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val matchesFilter = pkgFilter.isEmpty() ||
                        root.packageName?.toString().orEmpty() == pkgFilter
                    if (matchesFilter) {
                        val rootJson = nodeToJson(
                            node = root,
                            depth = 0,
                            maxDepth = maxDepth,
                            compact = compact,
                            hideNonVisible = hideNonVisible,
                            visited = visited,
                        )
                        if (rootJson != null) {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("active_window"))
                                    put("root", rootJson)
                                },
                            )
                        }
                    }
                } finally {
                    root.recycle()
                }
            }
        }
    }
    return buildJsonObject {
        put("package", JsonPrimitive(rootInActiveWindow?.packageName?.toString().orEmpty()))
        if (pkgFilter.isNotEmpty()) {
            put("filteredToPackage", JsonPrimitive(pkgFilter))
        }
        put("windows", windowsJson)
        put("nodesCaptured", JsonPrimitive(visited.count))
        put("compact", JsonPrimitive(compact))
    }
}

private class IntNodeCounter(private val max: Int) {
    var count: Int = 0
        private set

    fun exhausted(): Boolean = count >= max

    /** @return false if budget already exhausted */
    fun tryVisit(): Boolean {
        if (count >= max) return false
        count++
        return true
    }
}

private fun windowTypeLabel(type: Int): String = when (type) {
    AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
    AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
    else -> "other_$type"
}

@Suppress("DEPRECATION")
private fun isNodeVisible(node: AccessibilityNodeInfo): Boolean =
    node.isVisibleToUser

/**
 * @return null if node omitted (compact: invisible / boring leaf) or visit budget exhausted.
 */
@Suppress("LongMethod")
private fun nodeToJson(
    node: AccessibilityNodeInfo,
    depth: Int,
    maxDepth: Int,
    compact: Boolean,
    hideNonVisible: Boolean,
    visited: IntNodeCounter,
): JsonObject? {
    if (visited.exhausted()) return null
    if (compact && hideNonVisible && !isNodeVisible(node)) return null
    if (!visited.tryVisit()) return null

    val className = node.className?.toString().orEmpty()
    val text = node.text?.toString().orEmpty()
    val contentDescription = node.contentDescription?.toString().orEmpty()
    val viewId = node.viewIdResourceName.orEmpty()
    val clickable = node.isClickable
    val scrollable = node.isScrollable
    val editable = node.isEditable
    val enabled = node.isEnabled
    val focused = node.isFocused

    val interestingSelf =
        text.isNotBlank() ||
            contentDescription.isNotBlank() ||
            viewId.isNotBlank() ||
            clickable ||
            scrollable ||
            editable ||
            focused

    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    val childCount = node.childCount

    val childrenArray = buildJsonArray {
        if (depth < maxDepth && childCount > 0 && !visited.exhausted()) {
            for (i in 0 until childCount) {
                if (visited.exhausted()) break
                val ch = node.getChild(i) ?: continue
                try {
                    val childJson = nodeToJson(
                        node = ch,
                        depth = depth + 1,
                        maxDepth = maxDepth,
                        compact = compact,
                        hideNonVisible = hideNonVisible,
                        visited = visited,
                    )
                    if (childJson != null) add(childJson)
                } finally {
                    ch.recycle()
                }
            }
        }
    }

    val hasChildren = childrenArray.isNotEmpty()

    if (compact && !interestingSelf && !hasChildren) {
        return null
    }

    if (compact) {
        return buildJsonObject {
            if (className.isNotBlank()) put("cls", JsonPrimitive(shortClass(className)))
            putIfNonBlank("t", text)
            putIfNonBlank("d", contentDescription)
            putIfNonBlank("id", viewId)
            if (clickable) put("clk", JsonPrimitive(true))
            if (scrollable) put("scr", JsonPrimitive(true))
            if (editable) put("ed", JsonPrimitive(true))
            if (!enabled) put("dis", JsonPrimitive(true))
            if (focused) put("foc", JsonPrimitive(true))
            put("b", jsonBoundsCompact(bounds))
            if (hasChildren) put("c", childrenArray)
        }
    }

    return buildJsonObject {
        put("className", JsonPrimitive(className))
        put("text", JsonPrimitive(text))
        put("contentDescription", JsonPrimitive(contentDescription))
        put("viewIdResourceName", JsonPrimitive(viewId))
        put("clickable", JsonPrimitive(clickable))
        put("scrollable", JsonPrimitive(scrollable))
        put("editable", JsonPrimitive(editable))
        put("enabled", JsonPrimitive(enabled))
        put("focused", JsonPrimitive(focused))
        put("bounds", jsonBoundsFull(bounds))
        put("childCount", JsonPrimitive(childCount))
        put("children", childrenArray)
    }
}

private fun JsonObjectBuilder.putIfNonBlank(key: String, value: String) {
    if (value.isNotBlank()) put(key, JsonPrimitive(value))
}

private fun shortClass(fqn: String): String = fqn.substringAfterLast('.')

private fun jsonBoundsCompact(r: Rect): JsonObject = buildJsonObject {
    put("l", JsonPrimitive(r.left))
    put("t", JsonPrimitive(r.top))
    put("r", JsonPrimitive(r.right))
    put("bt", JsonPrimitive(r.bottom))
}

private fun jsonBoundsFull(r: Rect): JsonObject = buildJsonObject {
    put("left", JsonPrimitive(r.left))
    put("top", JsonPrimitive(r.top))
    put("right", JsonPrimitive(r.right))
    put("bottom", JsonPrimitive(r.bottom))
}
