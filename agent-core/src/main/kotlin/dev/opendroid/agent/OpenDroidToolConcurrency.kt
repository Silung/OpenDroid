package dev.opendroid.agent

/**
 * 与 claude-code 的 tool 编排类似：连续、无设备副作用的工具可在同一轮内并发执行
 *（例如 `load_skill` 与 `get_ui_tree` 重叠），从而降低总延迟。
 */
object OpenDroidToolConcurrency {
    private val concurrentSafeNames: Set<String> = setOf(
        "get_ui_tree",
        "get_focused_package",
        "capture_screenshot",
        "list_apps",
        "load_skill",
        "agent_wait",
    )

    fun isConcurrentSafe(name: String): Boolean = name in concurrentSafeNames

    /**
     * 将工具序列按「连续可并发」分段；段内要么全是 [isConcurrentSafe]，要么单个非安全工具.
     */
    fun partitionConsecutiveSafeBatches(tools: List<ContentBlock.ToolUse>): List<List<ContentBlock.ToolUse>> {
        if (tools.isEmpty()) return emptyList()
        val out = ArrayList<ArrayList<ContentBlock.ToolUse>>()
        var i = 0
        while (i < tools.size) {
            val t = tools[i]
            if (isConcurrentSafe(t.name)) {
                val group = ArrayList<ContentBlock.ToolUse>(4).apply { add(t) }
                i++
                while (i < tools.size && isConcurrentSafe(tools[i].name)) {
                    group.add(tools[i])
                    i++
                }
                out.add(group)
            } else {
                out.add(arrayListOf(t))
                i++
            }
        }
        return out
    }
}
