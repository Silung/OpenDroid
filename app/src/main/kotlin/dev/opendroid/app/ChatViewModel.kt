package dev.opendroid.app



import android.app.Application

import android.provider.Settings

import androidx.lifecycle.AndroidViewModel

import androidx.lifecycle.viewModelScope

import dev.opendroid.agent.AnthropicLlmClient

import dev.opendroid.agent.ChatMessage

import dev.opendroid.agent.FinishReason

import dev.opendroid.agent.OpenDroidQueryLoop

import dev.opendroid.agent.QueryLoopEvent

import dev.opendroid.agent.opendroidDefaultToolDefinitions

import dev.opendroid.app.session.ChatSessionRepository

import dev.opendroid.app.session.SessionSummary

import dev.opendroid.app.session.deriveSessionTitle

import dev.opendroid.device.opendroidDeviceToolExecutor

import dev.opendroid.app.skills.OpenDroidSkillRepository

import kotlinx.coroutines.CancellationException

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job

import kotlinx.coroutines.ensureActive

import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.combine

import kotlinx.coroutines.flow.update

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext

import dev.opendroid.app.overlay.FloatingOverlayService

import dev.opendroid.app.overlay.OpenDroidOverlayBridge

import java.util.UUID

import kotlinx.coroutines.delay



sealed class ChatLine {

    data class User(val text: String, val id: String = UUID.randomUUID().toString()) : ChatLine()

    data class Assistant(

        val text: String,

        val streaming: Boolean,

        val id: String = UUID.randomUUID().toString(),

    ) : ChatLine()



    data class Tool(

        val name: String,

        val toolUseId: String,

        val inputSummary: String,

        val ok: Boolean,

        val durationMs: Long,

        val resultTotalChars: Int,

        val resultPreview: String,

        val id: String = UUID.randomUUID().toString(),

    ) : ChatLine()

    data class System(val text: String, val id: String = UUID.randomUUID().toString()) : ChatLine()

}



private const val DEFAULT_SYSTEM = """You are OpenDroid, a phone automation agent.

You control the device through tools only; do not invent screen content.

**Navigation discipline:** After **any** step that can change the foreground app or window (launch_app, key_system Home/Back/Recents, or taps/swipes that open another app or a substantially new screen), the **first** `get_ui_tree` must be **`{}` without `packageName`**—the jump may have failed, a dialog/IME may be up, or focus may differ from what you expected; a package filter can hide the real screen or return an empty/wrong subtree. You may call **`get_focused_package` with `{}`** in parallel or right before for awareness, but do **not** pass `packageName` on that first tree read. **After** the tree (and/or `get_focused_package`) shows you are stably on the intended app, you **may** pass `packageName` on later `get_ui_tree` calls to trim status bar, IME, and unrelated windows.

For get_ui_tree: add keyword, maxDepth, maxNodes, compact, hideNonVisible when helpful; use **`packageName` only once navigation outcome is confirmed**, not immediately after a navigation attempt. JSON keys: cls,t,d,id,clk,scr,ed,b (l,t,r,bt), c(children).

**Hostile / sparse trees:** OpenDroid 只提供 **一项** 无障碍服务（系统中名称可与「随选朗读」组件相同）。若 **`{}` 与调参后树仍异常**，可再试带 **`\"accessibilityTreeSource\": \"whitelist_compat\"`**（与当前默认实为同一连接，便于固定重试流程）。若返回 **`accessibility_service_disabled`**，根据 **hint** 请用户在 设置 → 无障碍 中开启该项。

**If a parameterized get_ui_tree looks wrong or incomplete** (bad keyword/packageName, depth/nodes too tight, missing whole regions, tree empty or implausible): **call get_ui_tree again with `{}`** — no filters, tool defaults — to get a broader picture. Only **after that** still fails should you use capture_screenshot. Do not jump straight to screenshot when parameters were probably the issue.

Bounds `b` are **absolute screen pixels** (same space as Android getBoundsInScreen). For tap, swipe, long_press, and drag: use **`normalized: false`** (or omit it) and pass **pixel** x/y — e.g. tap center `((l+r)/2, (t+bt)/2)`. Do **not** use normalized=true with tree pixel values.

Use capture_screenshot only when: (1) you already retried **parameterless `{}` get_ui_tree** and the tree is still empty, tiny, or unusable; (2) WebView / custom surfaces / paywalls make the tree unreliable; (3) after those checks you still cannot find or verify the control; (4) repeated gestures show no UI change and tree stays unhelpful — then capture JPEG for vision instead of blind repeats.

**capture_screenshot result shape:** The tool_result **text** is **metadata JSON only** (width, height, ok, etc.). The **screenshot is attached as image block(s)** to that same tool result for vision — do not expect base64 or pixels inside the text string. Prefer **`{}`** for parameters unless you must reduce image size (maxLongEdge/maxShortEdge are downscale **caps**, not request dimensions). After vision, still align taps with **get_ui_tree** pixel bounds when possible.

Use agent_wait(duration_ms) to wait for animations, page loads, or when the user asks to pause; keep waits modest (often 500–2000 ms).

For list_apps: the user usually names the app clearly — **pass query** with a short substring of that name (or pinyin fragment / English) whenever possible; only use an empty / exploratory call when you truly need many apps at once.

Prefer short plans, then act. User language may be Chinese or English — reply in the user's language.

Never ask for passwords or 2FA codes.

The message will include a section listing local skills and tool names — use load_skill only when a skill clearly applies."""

private const val AGENT_OVERLAY_HIDE_DELAY_MS = 5_000L

/** 去掉正文开头的 \\n/\\r，避免模型首轮 SSE 带入的前导换行；不 trim 其它空白，以免破坏有意缩进。 */
private fun String.trimLeadingLineBreaks(): String =
    dropWhile { it == '\n' || it == '\r' }



class ChatViewModel(application: Application) : AndroidViewModel(application) {



    private val settings = OpenDroidSettings(application)

    private val skillRepo = OpenDroidSkillRepository(application)

    private val sessionRepo = ChatSessionRepository(application)

    private val conversation = mutableListOf<ChatMessage>()



    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())

    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()



    private val _busy = MutableStateFlow(false)

    val busy: StateFlow<Boolean> = _busy.asStateFlow()



    private val _input = MutableStateFlow("")

    val input: StateFlow<String> = _input.asStateFlow()



    private val _activeSessionId = MutableStateFlow("")

    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()



    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()



    private var agentJob: Job? = null

    private var overlayHideJob: Job? = null



    init {

        viewModelScope.launch {

            combine(lines, busy) { l, b -> l to b }.collect { (l, b) ->

                OpenDroidOverlayBridge.updateFromChat(getApplication(), l, b)

            }

        }

        viewModelScope.launch {

            restoreOrCreateSession()

        }

    }



    private suspend fun restoreOrCreateSession() {

        val summaries = sessionRepo.loadIndex()

        val savedId = settings.currentChatSessionId

        val idToLoad = when {

            savedId.isNotBlank() && summaries.any { it.id == savedId } -> savedId

            summaries.isNotEmpty() -> summaries.maxBy { it.updatedAt }.id

            else -> UUID.randomUUID().toString()

        }

        settings.currentChatSessionId = idToLoad

        _activeSessionId.value = idToLoad

        val loaded = sessionRepo.loadSession(idToLoad)

        if (loaded != null) {

            conversation.clear()

            conversation.addAll(loaded.conversation)

            _lines.value = loaded.lines.map { line ->

                if (line is ChatLine.Assistant && line.streaming) line.copy(streaming = false) else line

            }

        } else {

            conversation.clear()

            _lines.value = emptyList()

        }

        _sessions.value = sessionRepo.loadIndex()

    }



    private fun linesForPersistence(): List<ChatLine> = _lines.value.map { line ->

        if (line is ChatLine.Assistant && line.streaming) line.copy(streaming = false) else line

    }



    private suspend fun persistCurrentSession() {

        val id = _activeSessionId.value

        if (id.isBlank()) return

        val snap = linesForPersistence()

        if (snap.isEmpty() && conversation.isEmpty()) return

        val title = deriveSessionTitle(snap)

        withContext(Dispatchers.IO) {

            sessionRepo.saveSession(id, title, snap, conversation.toList())

        }

        _sessions.value = sessionRepo.loadIndex()

    }



    private fun stopAgentOverlayNow() {

        overlayHideJob?.cancel()

        overlayHideJob = null

        FloatingOverlayService.stop(getApplication())

        OpenDroidOverlayBridge.clear(getApplication())

    }



    /** Agent 运行前调用；若已开启悬浮窗开关且有权限则拉起前台服务。 */

    private fun tryStartOverlayForAgent(): Boolean {

        overlayHideJob?.cancel()

        overlayHideJob = null

        if (!settings.overlayEnabled) return false

        if (!Settings.canDrawOverlays(getApplication())) return false

        FloatingOverlayService.startIfNeeded(getApplication())

        return true

    }



    /** Agent 本轮结束后延迟关闭悬浮窗（保持最后画面至倒计时结束）。 */

    private fun scheduleOverlayHideAfterAgent() {

        overlayHideJob?.cancel()

        overlayHideJob = viewModelScope.launch {

            delay(AGENT_OVERLAY_HIDE_DELAY_MS)

            FloatingOverlayService.stop(getApplication())

            OpenDroidOverlayBridge.clear(getApplication())

            overlayHideJob = null

        }

    }



    fun newSession() {

        viewModelScope.launch {

            stopAgentOverlayNow()

            agentJob?.cancel()

            agentJob = null

            persistCurrentSession()

            val newId = UUID.randomUUID().toString()

            _activeSessionId.value = newId

            settings.currentChatSessionId = newId

            conversation.clear()

            _lines.value = emptyList()

            _busy.value = false

            _sessions.value = sessionRepo.loadIndex()

        }

    }



    fun selectSession(sessionId: String) {

        if (sessionId == _activeSessionId.value) return

        viewModelScope.launch {

            stopAgentOverlayNow()

            agentJob?.cancel()

            agentJob = null

            persistCurrentSession()

            _activeSessionId.value = sessionId

            settings.currentChatSessionId = sessionId

            val loaded = sessionRepo.loadSession(sessionId)

            conversation.clear()

            if (loaded != null) {

                conversation.addAll(loaded.conversation)

                _lines.value = loaded.lines.map { line ->

                    if (line is ChatLine.Assistant && line.streaming) line.copy(streaming = false) else line

                }

            } else {

                _lines.value = emptyList()

            }

            _busy.value = false

            _sessions.value = sessionRepo.loadIndex()

        }

    }



    fun updateInput(value: String) {

        _input.value = value

    }



    fun stopAgent() {

        agentJob?.cancel()

    }



    fun send() {

        val text = _input.value.trim()

        if (text.isEmpty() || _busy.value) return

        _input.value = ""

        agentJob = viewModelScope.launch {

            var overlayStartedThisRun = false

            try {

                _busy.value = true

                _lines.update { it + ChatLine.User(text) }

                val key = settings.anthropicApiKey

                if (key.isBlank()) {

                    _lines.update { it + ChatLine.System("请先在设置中填写 Anthropic API Key。") }

                    return@launch

                }

                overlayStartedThisRun = tryStartOverlayForAgent()

                val client = AnthropicLlmClient(

                    apiKey = key,

                    baseUrl = settings.anthropicBaseUrl,

                )

                val tools = opendroidDefaultToolDefinitions()

                val skills = withContext(Dispatchers.IO) { skillRepo.listSkills() }

                val toolNames = tools.map { it.name }

                val catalog = skillRepo.catalogMarkdownBlock(skills, toolNames)

                val systemPrompt = buildString {

                    appendLine(DEFAULT_SYSTEM.trim())

                    appendLine()

                    appendLine(catalog)

                }.trim()

                val loop = OpenDroidQueryLoop(

                    llm = client,

                    tools = tools,

                    toolExecutor = opendroidDeviceToolExecutor(getApplication(), skillRepo),

                )

                loop.run(

                    systemPrompt = systemPrompt,

                    conversation = conversation,

                    userText = text,

                    model = settings.model,

                    maxTurns = settings.maxTurns,

                    emit = { ev ->

                        ensureActive()

                        handleEvent(ev)

                    },

                )

            } catch (e: CancellationException) {

                markAssistantNotStreaming()

                _lines.update { it + ChatLine.System("任务已中止。") }

                throw e

            } finally {

                _busy.value = false

                agentJob = null

                persistCurrentSession()

                if (overlayStartedThisRun) {

                    scheduleOverlayHideAfterAgent()

                }

            }

        }

    }



    private fun handleEvent(ev: QueryLoopEvent) {

        when (ev) {

            is QueryLoopEvent.UserTurnAdded -> { /* user line already added */ }

            is QueryLoopEvent.AssistantDelta -> appendAssistantDelta(ev.text)

            is QueryLoopEvent.AssistantTurnFinished -> finalizeAssistantTurn(ev.text)

            is QueryLoopEvent.ToolStarted -> { }

            is QueryLoopEvent.ToolFinished -> {

                _lines.update {

                    it + ChatLine.Tool(

                        name = ev.name,

                        toolUseId = ev.toolUseId,

                        inputSummary = ev.inputSummary,

                        ok = ev.ok,

                        durationMs = ev.durationMs,

                        resultTotalChars = ev.resultTotalChars,

                        resultPreview = ev.resultPreview,

                    )

                }

            }

            is QueryLoopEvent.ConversationCompacted -> {

                _lines.update {

                    it + ChatLine.System(

                        "对话过长，已自动摘要早期内容以降低请求体积（估算负载约 ${ev.approxPayloadCharsBefore} 字符，摘要 ${ev.summaryCharCount} 字符）。",

                    )

                }

            }

            is QueryLoopEvent.Finished -> {

                when (val r = ev.reason) {

                    is FinishReason.Normal -> { }

                    is FinishReason.MaxTurns -> {

                        _lines.update { it + ChatLine.System("已达到 maxTurns 上限。") }

                    }

                    is FinishReason.Error -> {

                        _lines.update { it + ChatLine.System("错误: ${r.message}") }

                    }

                }

            }

        }

    }



    private fun appendAssistantDelta(chunk: String) {

        if (chunk.isEmpty()) return

        _lines.update { list ->

            val mut = list.toMutableList()

            val last = mut.lastOrNull()

            if (last is ChatLine.Assistant && last.streaming) {

                val merged = (last.text + chunk).trimLeadingLineBreaks()

                if (merged.isEmpty()) {

                    mut.removeAt(mut.lastIndex)

                } else {

                    mut[mut.lastIndex] = last.copy(text = merged, streaming = true)

                }

            } else {

                val text = chunk.trimLeadingLineBreaks()

                if (text.isEmpty()) return@update list

                mut.add(ChatLine.Assistant(text = text, streaming = true))

            }

            mut

        }

    }



    /** 每轮结束时用 API 汇总的助手正文对齐 UI，避免仅 tool 或未推送 text_delta 时留下空气泡。 */
    private fun finalizeAssistantTurn(aggregatedText: String) {

        val t = aggregatedText.trimEnd().trimLeadingLineBreaks()

        _lines.update { lines ->

            val mut = lines.toMutableList()

            val last = mut.lastOrNull()

            when (last) {

                is ChatLine.Assistant -> {

                    val merged = if (t.isNotEmpty()) t else last.text

                    if (merged.isBlank()) {

                        mut.removeAt(mut.lastIndex)

                    } else {

                        mut[mut.lastIndex] = last.copy(text = merged, streaming = false)

                    }

                }

                else -> {

                    if (t.isNotEmpty()) {

                        mut.add(ChatLine.Assistant(text = t, streaming = false))

                    }

                }

            }

            mut

        }

    }



    private fun markAssistantNotStreaming() {

        _lines.update { list ->

            val mut = list.toMutableList()

            val last = mut.lastOrNull()

            if (last is ChatLine.Assistant) {

                if (last.text.isBlank()) {

                    mut.removeAt(mut.lastIndex)

                } else {

                    mut[mut.lastIndex] = last.copy(streaming = false)

                }

            }

            mut

        }

    }



    fun clearConversation() {

        viewModelScope.launch {

            stopAgentOverlayNow()

            agentJob?.cancel()

            agentJob = null

            val oldId = _activeSessionId.value

            if (oldId.isNotBlank()) {

                withContext(Dispatchers.IO) { sessionRepo.deleteSession(oldId) }

            }

            val newId = UUID.randomUUID().toString()

            _activeSessionId.value = newId

            settings.currentChatSessionId = newId

            conversation.clear()

            _lines.value = emptyList()

            _busy.value = false

            OpenDroidOverlayBridge.clear(getApplication())

            _sessions.value = sessionRepo.loadIndex()

        }

    }

}


