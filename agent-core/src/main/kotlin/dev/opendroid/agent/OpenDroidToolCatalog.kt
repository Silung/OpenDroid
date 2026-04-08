package dev.opendroid.agent

/**
 * 内置设备 / Agent 工具的唯一注册入口（工具 schema 定义在 [opendroidDefaultToolDefinitions]）。
 *
 * [opendroidBuiltinToolIdsInOrder] 按 API 中的注册顺序列出 id（测试 / 文档用）。
 */
fun opendroidBuiltinToolIdsInOrder(): List<String> =
    opendroidDefaultToolDefinitions().map { it.name }

/** 本轮发往 LLM 的完整内置 tool 列表。 */
fun opendroidToolDefinitionsForSession(): List<ToolDefinition> =
    opendroidDefaultToolDefinitions()
