package dev.opendroid.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Built-in tool definitions for device automation (aligned with project README).
 */
fun opendroidDefaultToolDefinitions(): List<ToolDefinition> = listOf(
    ToolDefinition(
        name = "get_ui_tree",
        description =
            "Return accessibility UI tree JSON. **Right after a navigation attempt** (launch_app, key_system, tap/swipe that may change the app or screen), **omit `packageName`** on the first call—the jump may not have succeeded; filtering by an expected package can empty or distort the tree. After you confirm focus from this tree or **`get_focused_package`**, you may set `packageName` on later calls to drop status bar, IME, and other packages. Also use keyword, maxDepth, maxNodes, compact, hideNonVisible when helpful. " +
                "**Sparse / hostile trees** (some in-app WebViews or heavily filtered UIs): after `{}` and adjusted parameters still fail, you may set **`accessibilityTreeSource`: `\"whitelist_compat\"`** for compatibility with older agent prompts (OpenDroid uses a single accessibility service whose component name matches system Select-to-Speak). If the service is off, `accessibility_service_disabled` includes a **hint**—have the user enable OpenDroid in Settings → Accessibility. " +
                "**Recovery:** if a parameterized call clearly misses the screen (wrong/empty subtree, tree too thin, filters too tight), call **get_ui_tree again with `{}`**—no filters, default depth/nodes—before giving up on the tree. Broad defaults: compact=true, maxDepth=12, maxNodes=600, hideNonVisible=true. " +
                "Guidelines: (0) accessibilityTreeSource — optional; `default` (omit) vs `whitelist_compat` hit the **same** OpenDroid service today—keep for retry scripts. (1) packageName — omit for all windows until focus is confirmed. (2) keyword — short substring on label, id, desc (case-insensitive). (3) maxDepth — overview 6–10, typical 12–14, deep 16–18. " +
                "(4) maxNodes — focused 250–400, general 500–800, heavy 900–1500. (5) compact — true unless you need every attribute. (6) hideNonVisible — usually true.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "accessibilityTreeSource",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Optional. OpenDroid exposes **one** accessibility service (component name matches Android Select-to-Speak). `default` or omit and `whitelist_compat` both use that same connection—values kept for agent retry patterns and forward compatibility.",
                                ),
                            )
                        },
                    )
                    put(
                        "packageName",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Optional. Exact Android package id (e.g. dev.opendroid.app). When set, only windows whose accessibility **root** belongs to this package are returned—excludes system UI, input method windows, and other apps. Omit or empty = no package filter (all windows). **Do not set on the first get_ui_tree after a navigation attempt**—wait until the outcome is visible, then use for follow-up reads.",
                                ),
                            )
                        },
                    )
                    put(
                        "keyword",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Prefer setting when the user/task names a label, app, button, field, or id: substring on text, content-desc, view-id, class (case-insensitive). Empty = no filter.",
                                ),
                            )
                        },
                    )
                    put(
                        "maxDepth",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Traversal depth (1–32). Consider explicit values when useful: overview 6–10, typical 12–14, deep 16–18; default 12 if omitted.",
                                ),
                            )

                        },
                    )
                    put(
                        "compact",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "true (default): short keys, prune boring layout nodes—use almost always. false: verbose tree only when compact result lacks needed fields.",
                                ),
                            )
                        },
                    )
                    put(
                        "maxNodes",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Hard cap (50–2000). When useful, try 300–450 focused, 550–700 general, 900–1500 heavy screens (often with keyword). Default 600 if omitted.",
                                ),
                            )
                        },
                    )
                    put(
                        "hideNonVisible",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "true (default): drop non-visible nodes. Set false only when debugging visibility issues.",
                                ),
                            )
                        },
                    )
                },
            )
        },
    ),
    ToolDefinition(
        name = "get_focused_package",
        description =
            "Return JSON with `packageName` of the current accessibility **active window** (foreground UI you are automating) and `activeWindowAvailable` (bool). Cheap read—**call with `{}` right after** launch_app, key_system (Home/Back/Recents), or any tap/swipe that might change the visible app. Use it to **see** what is focused; the **first** `get_ui_tree` after such a step should still omit `packageName` because the intended navigation may have failed. If the service is off or no window root, `packageName` may be empty.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { })
        },
    ),
    ToolDefinition(
        name = "capture_screenshot",
        description =
            "Take a full-display **JPEG** via the accessibility screenshot API. **What you get back:** the tool_result **text** is a **tiny JSON** with ok, width, height, media_type, jpegByteLength, and a note — **not** the image bytes. " +
                "The **pixels are attached as separate image content** on the same tool_result (multimodal / vision). Read the image for UI you cannot get from the tree; still use **screen-pixel bounds from get_ui_tree** for tap/swipe when the tree is usable. " +
                "**When to call:** only after reasonable get_ui_tree attempts — parameterized tree, then **`get_ui_tree` with `{}`**, then if still empty/unusable or WebView-heavy / visually opaque, call **`capture_screenshot` with `{}`** (defaults are fine). Do not use width/height parameters to mean screen size; they only cap downscaling. " +
                "**Requirements:** Android 12 (API 31)+; in system settings the **accessibility service must allow full-screen screenshot**. Non–vision models will not see the image. Screenshots cost more tokens than trees.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "maxLongEdge",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Optional downscale cap: maximum allowed length in pixels of the **longer** side of the screenshot (physical width vs height). Range 640–2048; default 854 (≈480p 16:9 long side). The bitmap is shrunk **proportionally** so both long and short sides fit under caps; **never enlarged**. Not the output width in portrait — on a tall phone the long side is height.",
                                ),
                            )
                        },
                    )
                    put(
                        "maxShortEdge",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Optional downscale cap: maximum allowed length in pixels of the **shorter** side. Range 360–1080; default 480 (480p-class cap). Used together with maxLongEdge; same scale factor for both dimensions.",
                                ),
                            )
                        },
                    )
                    put(
                        "jpegQuality",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Optional JPEG compression 40–95; default 82. Higher = larger file / better detail.",
                                ),
                            )
                        },
                    )
                },
            )
        },
    ),
    ToolDefinition(
        name = "agent_wait",
        description =
            "Pause execution for UI/network animations to finish, or when the user asks to wait. " +
                "Uses Kotlin coroutine delay (no shell); cancellable if the user stops the run. " +
                "Safe to call alongside other read-only tools in the same turn. Prefer short waits (300–2000 ms) unless the user specifies longer.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "duration_ms",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Wait time in milliseconds. Clamped to 0..60000; default 1000 if omitted.",
                                ),
                            )
                        },
                    )
                },
            )
        },
    ),
    ToolDefinition(
        name = "tap",
        description =
            "Tap at screen coordinates. Use tree node bounds `b`: pixel center is ((l+r)/2, (t+bt)/2). " +
                "Set normalized=false with those pixels, OR normalized=true with x,y in **0..1 only** (fraction of screen width/height). " +
                "Never pass pixel values with normalized=true (e.g. 375,1812); use normalized=false for pixels. " +
                "Returns uiChanged: compact accessibility tree before vs after (~300ms settle).",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("x")); add(JsonPrimitive("y")) })
            put(
                "properties",
                buildJsonObject {
                    put(
                        "x",
                        buildJsonObject {
                            put("type", JsonPrimitive("number"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "If normalized=false: screen X in pixels (from UI tree b.l/b.r). If normalized=true: 0–1 fraction only.",
                                ),
                            )
                        },
                    )
                    put(
                        "y",
                        buildJsonObject {
                            put("type", JsonPrimitive("number"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "If normalized=false: screen Y in pixels (from UI tree b.t/b.bt). If normalized=true: 0–1 fraction only.",
                                ),
                            )
                        },
                    )
                    put(
                        "normalized",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "false (default for tree bounds): x/y are pixels. true: x/y must be 0–1; omit or false when using b.* pixel center.",
                                ),
                            )
                        },
                    )
                },
            )
        },
    ),
    ToolDefinition(
        name = "swipe",
        description =
            "Swipe from (x1,y1) to (x2,y2). Same coordinate rules as tap (pixels with normalized=false, or 0–1 with normalized=true). " +
                "Returns uiChanged (tree fingerprint before vs after).",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("x1")); add(JsonPrimitive("y1"))
                    add(JsonPrimitive("x2")); add(JsonPrimitive("y2"))
                },
            )
            put(
                "properties",
                buildJsonObject {
                    put("x1", buildJsonObject { put("type", JsonPrimitive("number")) })
                    put("y1", buildJsonObject { put("type", JsonPrimitive("number")) })
                    put("x2", buildJsonObject { put("type", JsonPrimitive("number")) })
                    put("y2", buildJsonObject { put("type", JsonPrimitive("number")) })
                    put("normalized", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                    put("durationMs", buildJsonObject { put("type", JsonPrimitive("integer")) })
                },
            )
        },
    ),
    ToolDefinition(
        name = "long_press",
        description =
            "Long-press at a point; coordinates same as tap (pixels + normalized=false preferred for tree bounds). " +
                "Returns uiChanged (tree fingerprint before vs after).",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("x")); add(JsonPrimitive("y")) })
            put(
                "properties",
                buildJsonObject {
                    put("x", buildJsonObject { put("type", JsonPrimitive("number")) })
                    put("y", buildJsonObject { put("type", JsonPrimitive("number")) })
                    put("normalized", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                    put("durationMs", buildJsonObject { put("type", JsonPrimitive("integer")) })
                },
            )
        },
    ),
    ToolDefinition(
        name = "input_text",
        description = "Set text on the currently focused editable field (ACTION_SET_TEXT). Tap the input first so it has IME focus. append=true concatenates onto existing text; false replaces. May fail on WebView content, password fields, or OEM custom editors.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("text")) })
            put(
                "properties",
                buildJsonObject {
                    put(
                        "text",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Full new value, or string to append when append=true"))
                        },
                    )
                    put(
                        "append",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("If true, append after current text; default false (replace)"))
                        },
                    )
                },
            )
        },
    ),
    ToolDefinition(
        name = "drag",
        description =
            "Drag through waypoints [[x,y], ...] in order. Coordinates same as tap (pixels with normalized=false). " +
                "Returns uiChanged (tree fingerprint before vs after).",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("points")) })
            put(
                "properties",
                buildJsonObject {
                    put(
                        "points",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", JsonPrimitive("array"))
                                    put("items", buildJsonObject { put("type", JsonPrimitive("number")) })
                                },
                            )
                        },
                    )
                    put("normalized", buildJsonObject { put("type", JsonPrimitive("boolean")) })
                    put("durationMs", buildJsonObject { put("type", JsonPrimitive("integer")) })
                },
            )
        },
    ),
    ToolDefinition(
        name = "key_system",
        description = "System navigation: HOME, BACK, or RECENTS.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("key")) })
            put(
                "properties",
                buildJsonObject {
                    put(
                        "key",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("HOME")); add(JsonPrimitive("BACK"))
                                    add(JsonPrimitive("RECENTS"))
                                },
                            )
                        },
                    )
                },
            )
        },
    ),
    ToolDefinition(
        name = "list_apps",
        description =
            "List installed apps as JSON: packageName + human label. **Almost always pass parameters**—users usually name the app clearly. " +
                "Set **query** to a short substring of the app display name or package (case-insensitive): e.g. user says 「微信」→ query \"微信\"; \"Chrome\"→ \"chrome\". " +
                "Avoid bare `{}` unless you truly need a broad catalog. Default launchable_only=true (launcher apps only); false for full PM list (Android 11+ may need QUERY_ALL_PACKAGES). " +
                "Use before launch_app to resolve packageName.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "launchable_only",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "true (default): launcher-visible apps only. false: broader install list.",
                                ),
                            )
                        },
                    )
                    put(
                        "limit",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Max rows (1–800), default 300. With a tight query, 30–80 is often enough.",
                                ),
                            )
                        },
                    )
                    put(
                        "query",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Strongly prefer setting this when the user or task names an app: substring match on label or packageName (case-insensitive). Omit only for exploratory listing.",
                                ),
                            )
                        },
                    )
                },
            )
        },
    ),
    ToolDefinition(
        name = "launch_app",
        description =
            "Start an app's main/launcher activity by package name. Prefer list_apps with a **query** matching the name the user said, then launch using the returned packageName. Uses FLAG_ACTIVITY_NEW_TASK; may fail if OS blocks background starts.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("packageName")) })
            put(
                "properties",
                buildJsonObject {
                    put(
                        "packageName",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Android applicationId / package name, e.g. com.android.chrome"))
                        },
                    )
                },
            )
        },
    ),
    ToolDefinition(
        name = "shell",
        description = "Run shell in user Linux environment (Termux). Not enabled in MVP.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("command")) })
            put(
                "properties",
                buildJsonObject {
                    put("command", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            )
        },
    ),
    ToolDefinition(
        name = "load_skill",
        description = "Load full local SKILL.md text. Parameter name MUST be the skill_id (directory name under app skills folder), exactly as listed in the system prompt skill catalog. Call when a skill description matches the user's task; skip if none match.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("name")) })
            put(
                "properties",
                buildJsonObject {
                    put("name", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            )
        },
    ),
)
