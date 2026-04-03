package dev.opendroid.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import dev.opendroid.agent.ToolExecutionResult
import dev.opendroid.agent.optBoolean
import dev.opendroid.agent.optDouble
import dev.opendroid.agent.optInt
import dev.opendroid.agent.optString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AndroidDeviceBridge(
    app: Context,
) {
    private val appContext = app.applicationContext
    private val json = Json { prettyPrint = false }
    private val main = Dispatchers.Main.immediate

    private fun serviceForGestures(): OpenDroidAccessibilityService? =
        OpenDroidAccessibilityHolder.serviceForGestures()

    private fun parseAccessibilityTreeSource(input: JsonObject): OpenDroidAccessibilityTreeSource {
        val raw = input.optString("accessibilityTreeSource", "default").trim().lowercase()
        return when (raw) {
            "whitelist_compat", "whitelist", "app_whitelist_compat" ->
                OpenDroidAccessibilityTreeSource.WHITELIST_COMPAT
            else -> OpenDroidAccessibilityTreeSource.DEFAULT
        }
    }

    private fun jsonTreeWithAccessibilitySource(
        tree: JsonObject,
        source: OpenDroidAccessibilityTreeSource,
    ): JsonObject = buildJsonObject {
        tree.forEach { (k, v) -> put(k, v) }
        if (source == OpenDroidAccessibilityTreeSource.WHITELIST_COMPAT) {
            put("accessibilityTreeSource", JsonPrimitive("whitelist_compat"))
        }
    }

    private fun accessibilityServiceDisabledJson(): JsonObject = buildJsonObject {
        put("error", JsonPrimitive("accessibility_service_disabled"))
        put(
            "hint",
            JsonPrimitive(
                "请在 设置 → 无障碍 中开启 OpenDroid；系统中可能显示为与「随选朗读」(Select-to-Speak) 相同的组件名。",
            ),
        )
    }

    /** 对比无障碍树 JSON 字符串（紧凑、有节点上限），用于判断手势后界面是否变化。 */
    private fun uiFingerprint(svc: OpenDroidAccessibilityService): String =
        json.encodeToString(
            JsonObject.serializer(),
            svc.captureUiForestJson(
                maxDepth = 10,
                compact = true,
                maxNodes = 400,
                hideNonVisible = true,
            ),
        )

    private suspend fun uiChangedAfterGesture(
        svc: OpenDroidAccessibilityService,
        gestureOk: Boolean,
        beforeFingerprint: String,
    ): Boolean {
        if (!gestureOk) return false
        delay(UI_CHANGE_SETTLE_MS)
        return beforeFingerprint != uiFingerprint(svc)
    }

    companion object {
        private const val UI_CHANGE_SETTLE_MS = 300L
    }

    /** 当前无障碍「活动窗口」根节点的包名（与 [captureUiForestJson] 顶层 `package` 同源）。 */
    suspend fun getFocusedPackage(@Suppress("UNUSED_PARAMETER") input: JsonObject): ToolExecutionResult =
        withContext(main) {
            val svc = serviceForGestures()
                ?: return@withContext ToolExecutionResult(
                    json.encodeToString(JsonObject.serializer(), accessibilityServiceDisabledJson()),
                    isError = true,
                )
            val root = svc.rootInActiveWindow
            if (root == null) {
                return@withContext ToolExecutionResult(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("packageName", JsonPrimitive(""))
                            put("activeWindowAvailable", JsonPrimitive(false))
                        },
                    ),
                )
            }
            try {
                val pkg = root.packageName?.toString().orEmpty()
                ToolExecutionResult(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("packageName", JsonPrimitive(pkg))
                            put("activeWindowAvailable", JsonPrimitive(true))
                        },
                    ),
                )
            } finally {
                root.recycle()
            }
        }

    suspend fun getUiTree(input: JsonObject): ToolExecutionResult = withContext(main) {
        val treeSource = parseAccessibilityTreeSource(input)
        val svc = OpenDroidAccessibilityHolder.serviceForTree(treeSource)
            ?: return@withContext ToolExecutionResult(
                json.encodeToString(JsonObject.serializer(), accessibilityServiceDisabledJson()),
                isError = true,
            )
        val depth = input.optInt("maxDepth", 12).coerceIn(1, 32)
        val compact = input.optBoolean("compact", true)
        val maxNodes = input.optInt("maxNodes", 600).coerceIn(50, 2000)
        val hideNonVisible = input.optBoolean("hideNonVisible", true)
        val packageNameFilter = input.optString("packageName", "").trim()
        val keyword = input.optString("keyword", "").trim()
        val tree = svc.captureUiForestJson(
            maxDepth = depth,
            compact = compact,
            maxNodes = maxNodes,
            hideNonVisible = hideNonVisible,
            packageNameFilter = packageNameFilter.takeIf { it.isNotEmpty() },
        )
        val merged =
            if (keyword.isNotEmpty()) {
                filterUiForestByKeyword(tree, keyword)
            } else {
                tree
            }
        val out = jsonTreeWithAccessibilitySource(merged, treeSource)
        ToolExecutionResult(json.encodeToString(JsonObject.serializer(), out))
    }

    suspend fun captureScreenshot(input: JsonObject): ToolExecutionResult = withContext(main) {
        val svc = serviceForGestures()
            ?: return@withContext ToolExecutionResult(
                json.encodeToString(JsonObject.serializer(), accessibilityServiceDisabledJson()),
                isError = true,
            )
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return@withContext ToolExecutionResult(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put(
                            "error",
                            JsonPrimitive("capture_screenshot_requires_android_12_api_31"),
                        )
                        put(
                            "hint",
                            JsonPrimitive("无障碍全屏截图需要 Android 12（API 31）及以上，并在无障碍配置中允许截图。"),
                        )
                    },
                ),
                isError = true,
            )
        }
        val maxLongEdge = input.optInt("maxLongEdge", 854).coerceIn(640, 2048)
        val maxShortEdge = input.optInt("maxShortEdge", 480).coerceIn(360, 1080)
        val jpegQuality = input.optInt("jpegQuality", 82).coerceIn(40, 95)
        svc.captureScreenshotForAgent(maxLongEdge, maxShortEdge, jpegQuality)
    }

    /** 不切换到 Main，便于与无障碍 Main 线程工具在并发批次中重叠执行。 */
    suspend fun agentWait(input: JsonObject): ToolExecutionResult {
        val requested = input.optInt("duration_ms", 1_000).coerceIn(0, 60_000)
        delay(requested.toLong())
        return ToolExecutionResult(
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("ok", JsonPrimitive(true))
                    put("waited_ms", JsonPrimitive(requested))
                },
            ),
        )
    }

    suspend fun tap(input: JsonObject): ToolExecutionResult = withContext(main) {
        val svc = serviceForGestures() ?: return@withContext disabled()
        val norm = input.optBoolean("normalized", false)
        val (px, py) = resolvePair(input.optDouble("x", 0.0), input.optDouble("y", 0.0), norm)
        val before = uiFingerprint(svc)
        val ok = svc.tapAt(px, py)
        val uiChanged = uiChangedAfterGesture(svc, ok, before)
        ToolExecutionResult("{\"ok\":$ok,\"x\":$px,\"y\":$py,\"uiChanged\":$uiChanged}")
    }

    suspend fun swipe(input: JsonObject): ToolExecutionResult = withContext(main) {
        val svc = serviceForGestures() ?: return@withContext disabled()
        val norm = input.optBoolean("normalized", false)
        val (x1, y1) = resolvePair(input.optDouble("x1", 0.0), input.optDouble("y1", 0.0), norm)
        val (x2, y2) = resolvePair(input.optDouble("x2", 0.0), input.optDouble("y2", 0.0), norm)
        val dur = input.optInt("durationMs", 320).toLong()
        val before = uiFingerprint(svc)
        val ok = svc.swipeLine(x1, y1, x2, y2, dur)
        val uiChanged = uiChangedAfterGesture(svc, ok, before)
        ToolExecutionResult("{\"ok\":$ok,\"uiChanged\":$uiChanged}")
    }

    suspend fun longPress(input: JsonObject): ToolExecutionResult = withContext(main) {
        val svc = serviceForGestures() ?: return@withContext disabled()
        val norm = input.optBoolean("normalized", false)
        val (px, py) = resolvePair(input.optDouble("x", 0.0), input.optDouble("y", 0.0), norm)
        val dur = input.optInt("durationMs", 650).toLong()
        val before = uiFingerprint(svc)
        val ok = svc.tapAt(px, py, dur.coerceAtLeast(100L))
        val uiChanged = uiChangedAfterGesture(svc, ok, before)
        ToolExecutionResult("{\"ok\":$ok,\"longPress\":true,\"uiChanged\":$uiChanged}")
    }

    suspend fun drag(input: JsonObject): ToolExecutionResult = withContext(main) {
        val svc = serviceForGestures() ?: return@withContext disabled()
        val norm = input.optBoolean("normalized", false)
        val arr = input["points"]?.jsonArray ?: return@withContext ToolExecutionResult(
            "{\"error\":\"missing_points\"}",
            isError = true,
        )
        val pts = parsePoints(arr, norm) ?: return@withContext ToolExecutionResult(
            "{\"error\":\"invalid_points\"}",
            isError = true,
        )
        val dur = input.optInt("durationMs", 450).toLong()
        val before = uiFingerprint(svc)
        val ok = svc.dragPoints(pts, dur)
        val uiChanged = uiChangedAfterGesture(svc, ok, before)
        ToolExecutionResult("{\"ok\":$ok,\"segments\":${pts.size},\"uiChanged\":$uiChanged}")
    }

    suspend fun inputText(input: JsonObject): ToolExecutionResult = withContext(main) {
        val svc = serviceForGestures() ?: return@withContext disabled()
        val text = input.optString("text")
        val append = input.optBoolean("append", false)
        when (val code = svc.inputTextToFocused(text, append)) {
            "ok" -> ToolExecutionResult(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("append", JsonPrimitive(append))
                    },
                ),
            )
            else -> ToolExecutionResult(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("ok", JsonPrimitive(false))
                        put("error", JsonPrimitive(code))
                    },
                ),
                isError = true,
            )
        }
    }

    suspend fun keySystem(input: JsonObject): ToolExecutionResult = withContext(main) {
        val svc = serviceForGestures() ?: return@withContext disabled()
        val key = input.optString("key", "").uppercase()
        val action = when (key) {
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return@withContext ToolExecutionResult("{\"error\":\"bad_key\"}", true)
        }
        val ok = svc.performGlobalAction(action)
        ToolExecutionResult("{\"ok\":$ok,\"key\":\"$key\"}")
    }

    suspend fun listApps(input: JsonObject): ToolExecutionResult = withContext(Dispatchers.IO) {
        val launchableOnly = input.optBoolean("launchable_only", true)
        val limit = input.optInt("limit", 300).coerceIn(1, 800)
        val queryFilter = input.optString("query").trim().lowercase()
        val pm = appContext.packageManager

        val rows: List<Pair<String, String>> = if (launchableOnly) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            val infos = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
            infos
                .map { ri ->
                    val pkg = ri.activityInfo.packageName
                    val label = ri.loadLabel(pm).toString()
                    pkg to label
                }
                .distinctBy { it.first }
        } else {
            val apps = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
            apps.map { ai ->
                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrElse { ai.packageName }
                ai.packageName to label
            }
        }

        val filtered = (
            if (queryFilter.isEmpty()) {
                rows
            } else {
                rows.filter { (pkg, label) ->
                    pkg.lowercase().contains(queryFilter) || label.lowercase().contains(queryFilter)
                }
            }
            ).sortedBy { it.second.lowercase() }

        val truncated = filtered.size > limit
        val slice = filtered.take(limit)

        val arr = buildJsonArray {
            for ((pkg, label) in slice) {
                add(
                    buildJsonObject {
                        put("packageName", JsonPrimitive(pkg))
                        put("label", JsonPrimitive(label))
                    },
                )
            }
        }
        val out = buildJsonObject {
            put("apps", arr)
            put("count", JsonPrimitive(slice.size))
            put("totalMatched", JsonPrimitive(filtered.size))
            put("truncated", JsonPrimitive(truncated))
            put("launchable_only", JsonPrimitive(launchableOnly))
        }
        ToolExecutionResult(json.encodeToString(JsonObject.serializer(), out))
    }

    suspend fun launchApp(input: JsonObject): ToolExecutionResult = withContext(main) {
        val pkg = input.optString("packageName").trim()
        if (pkg.isBlank()) {
            return@withContext ToolExecutionResult(
                json.encodeToString(JsonObject.serializer(), errJson("missing_packageName")),
                isError = true,
            )
        }
        val pm = appContext.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            return@withContext ToolExecutionResult(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("error", JsonPrimitive("no_launch_intent"))
                        put("packageName", JsonPrimitive(pkg))
                    },
                ),
                isError = true,
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = runCatching { appContext.startActivity(intent) }
        result.fold(
            onSuccess = {
                ToolExecutionResult(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("ok", JsonPrimitive(true))
                            put("packageName", JsonPrimitive(pkg))
                        },
                    ),
                )
            },
            onFailure = { e ->
                ToolExecutionResult(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("error", JsonPrimitive(e.message ?: "start_failed"))
                            put("packageName", JsonPrimitive(pkg))
                        },
                    ),
                    isError = true,
                )
            },
        )
    }

    /**
     * 与 [UiTreeJson] 中 [android.view.accessibility.AccessibilityNodeInfo.getBoundsInScreen]
     * 对齐：无障碍手势与 bounds 均使用**屏幕**像素；此处用 real / maximum 尺寸，避免
     * [android.content.res.Resources.getDisplayMetrics] 在部分机型上小于真实屏高导致归一化或钳位偏差。
     */
    private fun screenSizePx(): Pair<Float, Float> {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val w: Float
        val h: Float
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            w = b.width().toFloat()
            h = b.height().toFloat()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels.toFloat()
            h = dm.heightPixels.toFloat()
        }
        if (w <= 0f || h <= 0f) {
            val dm = appContext.resources.displayMetrics
            return dm.widthPixels.toFloat().coerceAtLeast(1f) to dm.heightPixels.toFloat().coerceAtLeast(1f)
        }
        return w to h
    }

    /**
     * `normalized=true` 时 x/y 须为 [0,1] 比例。模型常误把 **get_bounds 像素** 配上 normalized，例如 (375,1812)
     * 会被乘屏宽/高再钳到右下角。任一分量大于 1 时改按**像素**解释（与 normalized=false 相同）。
     */
    private fun useFractionCoords(normalizedFlag: Boolean, x: Double, y: Double): Boolean =
        normalizedFlag && x <= 1.0 && y <= 1.0

    private fun resolvePair(x: Double, y: Double, normalized: Boolean): Pair<Float, Float> {
        val (w, h) = screenSizePx()
        return if (useFractionCoords(normalized, x, y)) {
            (x * w).toFloat().coerceIn(0f, w) to (y * h).toFloat().coerceIn(0f, h)
        } else {
            x.toFloat().coerceIn(0f, w) to y.toFloat().coerceIn(0f, h)
        }
    }

    private fun parsePoints(arr: JsonArray, normalized: Boolean): List<Pair<Float, Float>>? {
        val (w, h) = screenSizePx()
        var fractionMode = normalized
        if (normalized) {
            for (el in arr) {
                val pair = el.jsonArray
                if (pair.size < 2) return null
                val xd = pair[0].jsonPrimitive.content.toDouble()
                val yd = pair[1].jsonPrimitive.content.toDouble()
                if (!useFractionCoords(true, xd, yd)) {
                    fractionMode = false
                    break
                }
            }
        }
        val out = ArrayList<Pair<Float, Float>>(arr.size)
        for (el in arr) {
            val pair = el.jsonArray
            if (pair.size < 2) return null
            val xd = pair[0].jsonPrimitive.content.toDouble()
            val yd = pair[1].jsonPrimitive.content.toDouble()
            val px: Float
            val py: Float
            if (fractionMode) {
                px = (xd * w).toFloat().coerceIn(0f, w)
                py = (yd * h).toFloat().coerceIn(0f, h)
            } else {
                px = xd.toFloat().coerceIn(0f, w)
                py = yd.toFloat().coerceIn(0f, h)
            }
            out.add(px to py)
        }
        return out
    }

    private fun disabled() = ToolExecutionResult(
        json.encodeToString(JsonObject.serializer(), accessibilityServiceDisabledJson()),
        isError = true,
    )

    private fun errJson(msg: String) = buildJsonObject {
        put("error", JsonPrimitive(msg))
    }
}

fun opendroidDeviceToolExecutor(
    context: Context,
    skills: dev.opendroid.agent.SkillContentLoader? = null,
): dev.opendroid.agent.ToolExecutor {
    val bridge = AndroidDeviceBridge(context)
    val handlers = mutableMapOf<String, suspend (JsonObject) -> ToolExecutionResult>(
        "get_ui_tree" to { bridge.getUiTree(it) },
        "get_focused_package" to { bridge.getFocusedPackage(it) },
        "agent_wait" to { bridge.agentWait(it) },
        "capture_screenshot" to { bridge.captureScreenshot(it) },
        "tap" to { bridge.tap(it) },
        "swipe" to { bridge.swipe(it) },
        "long_press" to { bridge.longPress(it) },
        "input_text" to { bridge.inputText(it) },
        "drag" to { bridge.drag(it) },
        "key_system" to { bridge.keySystem(it) },
        "list_apps" to { bridge.listApps(it) },
        "launch_app" to { bridge.launchApp(it) },
        "shell" to {
            ToolExecutionResult(
                "Termux/shell is not enabled yet. See README Phase P2 (RUN_COMMAND intent).",
                isError = true,
            )
        },
    )
    handlers["load_skill"] = { input ->
        val loader = skills
        if (loader == null) {
            ToolExecutionResult("Skill loader not configured.", isError = true)
        } else {
            val name = input.optString("name")
            val text = loader.load(name)
            if (text == null) {
                ToolExecutionResult("Skill not found: $name", isError = true)
            } else {
                ToolExecutionResult(text)
            }
        }
    }
    return dev.opendroid.agent.CompositeToolExecutor(handlers)
}
