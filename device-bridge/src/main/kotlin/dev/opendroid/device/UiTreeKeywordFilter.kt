package dev.opendroid.device

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 按关键词过滤 [captureUiForestJson] 产出的树：**保留命中节点及其祖先链上的子树**
 *（子树内仍保留未命中分支，只要路径上存在命中即可保留结构）。
 *
 * 匹配规则：不区分大小写；仅在字符串字段上匹配子串（compact：`t`、`d`、`id`、`cls`；
 * 完整树：`text`、`contentDescription`、`viewIdResourceName`、`className`）。
 * 窗口 `title` 命中则返回该窗口的**完整** root，不做裁剪。
 */
fun filterUiForestByKeyword(forest: JsonObject, keywordRaw: String): JsonObject {
    val keyword = keywordRaw.trim()
    if (keyword.isEmpty()) return forest
    val needle = keyword.lowercase()

    val windowsEl = forest["windows"] ?: return forest
    val windowsArr = windowsEl as? JsonArray ?: return forest

    val newWindows = buildJsonArray {
        for (el in windowsArr) {
            val win = el as? JsonObject ?: continue
            val filtered = filterWindow(win, needle) ?: continue
            add(filtered)
        }
    }

    return buildJsonObject {
        forest.forEach { (k, v) ->
            when (k) {
                "windows" -> put("windows", newWindows)
                else -> put(k, v)
            }
        }
        put("keywordFilter", JsonPrimitive(keyword))
        put("keywordFilterApplied", JsonPrimitive(true))
        if (newWindows.isEmpty()) {
            put("keywordMatchEmpty", JsonPrimitive(true))
            put(
                "keywordFilterHint",
                JsonPrimitive(
                    "无匹配节点，可更换关键词、不传 keyword 看全树、compact=false 或使用 capture_screenshot。",
                ),
            )
        }
    }
}

private fun filterWindow(win: JsonObject, needle: String): JsonObject? {
    val titleStr = win["title"]?.jsonPrimitive?.content.orEmpty()
    if (titleStr.lowercase().contains(needle)) {
        return win
    }
    val root = win["root"]?.jsonObject ?: return null
    val filteredRoot = filterSubtree(root, needle) ?: return null
    return buildJsonObject {
        win.forEach { (k, v) ->
            when (k) {
                "root" -> put("root", filteredRoot)
                else -> put(k, v)
            }
        }
    }
}

private fun childKey(node: JsonObject): String? =
    when {
        node.containsKey("c") -> "c"
        node.containsKey("children") -> "children"
        else -> null
    }

/** 在节点的可读字符串字段中查找 [needle]（小写）。 */
private fun nodeMatches(node: JsonObject, needle: String): Boolean {
    for ((k, v) in node) {
        if (k in KEYS_SKIP_FOR_MATCH) continue
        if (v !is JsonPrimitive || !v.isString) continue
        if (v.content.lowercase().contains(needle)) return true
    }
    return false
}

private val KEYS_SKIP_FOR_MATCH =
    setOf(
        // bounds / geometry
        "b",
        "bounds",
        "childCount",
        // booleans (compact)
        "clk",
        "scr",
        "ed",
        "dis",
        "foc",
        // booleans (verbose)
        "clickable",
        "scrollable",
        "editable",
        "enabled",
        "focused",
    )

/**
 * 若节点自身命中或任一子节点（过滤后）仍保留，则返回新节点；否则 null。
 * 若子项全部裁掉但自身命中，则返回仅含自身字段的节点（不含空的 `c`/`children`）。
 */
private fun filterSubtree(node: JsonObject, needle: String): JsonObject? {
    val ck = childKey(node)
    val rawArr = ck?.let { node[it] as? JsonArray }
    val newKids =
        rawArr?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            filterSubtree(obj, needle)
        }.orEmpty()

    val selfMatch = nodeMatches(node, needle)
    if (!selfMatch && newKids.isEmpty()) return null

    if (ck == null) {
        return buildJsonObject { node.forEach { (k, v) -> put(k, v) } }
    }

    return buildJsonObject {
        node.forEach { (k, v) ->
            if (k == ck) return@forEach
            put(k, v)
        }
        if (newKids.isNotEmpty()) {
            put(ck, buildJsonArray { newKids.forEach { add(it) } })
        }
    }
}
