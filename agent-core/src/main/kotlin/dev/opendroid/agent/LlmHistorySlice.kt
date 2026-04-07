package dev.opendroid.agent

/**
 * 发往 LLM 前截取：**仅限制 [maxAssistantMessages] 条 [ChatRole.Assistant]**。
 * [ChatRole.User] 不设单独条数上限；为满足 Assistant 上限会从左侧整体截断，User 随之减少，但**不会**为满足「总条数」而单独丢弃 User。
 *
 * 在「尾部 Assistant 数 ≤ 上限」的前提下，优先选取以 User 开头的最长后缀；若无此类起点，则退化为任意合法起点。
 *
 * @param firstMessageIndexToKeep 若设（通常为 [OpenDroidQueryLoop.run] 里本轮用户话的索引），则切片起点 **不得晚于** 该条，
 * 避免同一轮内多轮 tool 后助手条数触顶把**本轮用户自然语言**切掉；若与 cap 不可兼得，**宁可超出 Assistant 上限也保留该条起算的后缀**。
 */
fun sliceChatMessagesForLlmRequest(
    messages: List<ChatMessage>,
    maxAssistantMessages: Int,
    firstMessageIndexToKeep: Int? = null,
): List<ChatMessage> {
    val cap = maxAssistantMessages.coerceIn(1, 256)
    val n = messages.size
    if (n == 0) return messages

    val pin = firstMessageIndexToKeep?.coerceIn(0, n - 1)

    fun assistantCount(from: Int): Int =
        messages.subList(from, n).count { it.role == ChatRole.Assistant }

    var validStarts = (0 until n).filter { s ->
        assistantCount(s) <= cap && (pin == null || s <= pin)
    }
    if (validStarts.isEmpty()) {
        validStarts =
            if (pin != null) {
                // 保留本轮用户消息优先于 Assistant 条数 cap
                listOf(pin)
            } else {
                // 理论上极少发生；保留最后一条以免空请求
                listOf((n - 1).coerceAtLeast(0))
            }
    }

    val preferUserStart = validStarts.filter { s -> messages[s].role == ChatRole.User }
    val start = (if (preferUserStart.isNotEmpty()) preferUserStart else validStarts).minOrNull()!!
    return messages.subList(start, n)
}
