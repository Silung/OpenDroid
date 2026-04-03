package dev.opendroid.agent

import kotlinx.serialization.json.JsonObject

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)
