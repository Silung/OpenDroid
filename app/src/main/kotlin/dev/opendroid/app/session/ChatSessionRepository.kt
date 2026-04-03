package dev.opendroid.app.session

import android.content.Context
import dev.opendroid.app.ChatLine
import dev.opendroid.agent.ChatMessage
import dev.opendroid.agent.ChatRole
import dev.opendroid.agent.ContentBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.UUID

data class SessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
)

data class LoadedChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val lines: List<ChatLine>,
    val conversation: List<ChatMessage>,
)

class ChatSessionRepository(context: Context) {
    //
    private val root = File(context.filesDir, "chat_sessions").also { it.mkdirs() }
    private val indexFile = File(root, "index.json")
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    suspend fun loadIndex(): List<SessionSummary> = withContext(Dispatchers.IO) {
        if (!indexFile.isFile) return@withContext emptyList()
        runCatching {
            val arr = json.parseToJsonElement(indexFile.readText(Charsets.UTF_8)).jsonArray
            arr.map { el ->
                val o = el.jsonObject
                SessionSummary(
                    id = o["id"]!!.jsonPrimitive.content,
                    title = o["title"]?.jsonPrimitive?.content.orEmpty(),
                    updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun writeIndex(entries: List<SessionSummary>) {
        val sorted = entries.sortedByDescending { it.updatedAt }
        val arr = buildJsonArray {
            for (e in sorted) {
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(e.id))
                        put("title", JsonPrimitive(e.title))
                        put("updatedAt", JsonPrimitive(e.updatedAt))
                    },
                )
            }
        }
        indexFile.writeText(json.encodeToString(JsonArray.serializer(), arr), Charsets.UTF_8)
    }

    suspend fun loadSession(id: String): LoadedChatSession? = withContext(Dispatchers.IO) {
        val f = File(root, "$id.json")
        if (!f.isFile) return@withContext null
        runCatching { parseSessionFile(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    suspend fun saveSession(
        id: String,
        title: String,
        lines: List<ChatLine>,
        conversation: List<ChatMessage>,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val payload = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("title", JsonPrimitive(title))
            put("updatedAt", JsonPrimitive(now))
            put("lines", encodeLines(lines))
            put("conversation", encodeConversation(conversation))
        }
        File(root, "$id.json").writeText(
            json.encodeToString(JsonObject.serializer(), payload),
            Charsets.UTF_8,
        )
        val current = loadIndex().toMutableList()
        current.removeAll { it.id == id }
        current.add(SessionSummary(id, title, now))
        writeIndex(current)
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        File(root, "$id.json").delete()
        val current = loadIndex().filter { it.id != id }
        writeIndex(current)
    }

    private fun parseSessionFile(text: String): LoadedChatSession {
        val o = json.parseToJsonElement(text).jsonObject
        return LoadedChatSession(
            id = o["id"]!!.jsonPrimitive.content,
            title = o["title"]?.jsonPrimitive?.content.orEmpty(),
            updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            lines = decodeLines(o["lines"]?.jsonArray ?: buildJsonArray { }),
            conversation = decodeConversation(o["conversation"]?.jsonArray ?: buildJsonArray { }),
        )
    }

    private fun encodeLines(lines: List<ChatLine>): JsonArray = buildJsonArray {
        for (line in lines) {
            add(
                when (line) {
                    is ChatLine.User -> buildJsonObject {
                        put("k", JsonPrimitive("u"))
                        put("id", JsonPrimitive(line.id))
                        put("text", JsonPrimitive(line.text))
                    }
                    is ChatLine.Assistant -> buildJsonObject {
                        put("k", JsonPrimitive("a"))
                        put("id", JsonPrimitive(line.id))
                        put("text", JsonPrimitive(line.text))
                        put("st", JsonPrimitive(line.streaming))
                    }
                    is ChatLine.Tool -> buildJsonObject {
                        put("k", JsonPrimitive("t"))
                        put("id", JsonPrimitive(line.id))
                        put("name", JsonPrimitive(line.name))
                        put("toolUseId", JsonPrimitive(line.toolUseId))
                        put("inputSummary", JsonPrimitive(line.inputSummary))
                        put("ok", JsonPrimitive(line.ok))
                        put("durationMs", JsonPrimitive(line.durationMs))
                        put("resultTotalChars", JsonPrimitive(line.resultTotalChars))
                        put("resultPreview", JsonPrimitive(line.resultPreview))
                    }
                    is ChatLine.System -> buildJsonObject {
                        put("k", JsonPrimitive("s"))
                        put("id", JsonPrimitive(line.id))
                        put("text", JsonPrimitive(line.text))
                    }
                },
            )
        }
    }

    private fun decodeLines(arr: JsonArray): List<ChatLine> = arr.map { el ->
        val o = el.jsonObject
        when (o["k"]!!.jsonPrimitive.content) {
            "u" -> ChatLine.User(
                text = o["text"]!!.jsonPrimitive.content,
                id = o["id"]!!.jsonPrimitive.content,
            )
            "a" -> ChatLine.Assistant(
                text = o["text"]!!.jsonPrimitive.content,
                streaming = o["st"]?.jsonPrimitive?.booleanOrNull == true,
                id = o["id"]!!.jsonPrimitive.content,
            )
            "t" -> ChatLine.Tool(
                name = o["name"]!!.jsonPrimitive.content,
                toolUseId = o["toolUseId"]!!.jsonPrimitive.content,
                inputSummary = o["inputSummary"]!!.jsonPrimitive.content,
                ok = o["ok"]!!.jsonPrimitive.boolean,
                durationMs = o["durationMs"]!!.jsonPrimitive.content.toLong(),
                resultTotalChars = o["resultTotalChars"]!!.jsonPrimitive.content.toInt(),
                resultPreview = o["resultPreview"]!!.jsonPrimitive.content,
                id = o["id"]!!.jsonPrimitive.content,
            )
            "s" -> ChatLine.System(
                text = o["text"]!!.jsonPrimitive.content,
                id = o["id"]!!.jsonPrimitive.content,
            )
            else -> ChatLine.System("（无法解析的行）", UUID.randomUUID().toString())
        }
    }

    private fun encodeConversation(msgs: List<ChatMessage>): JsonArray = buildJsonArray {
        for (m in msgs) {
            add(
                buildJsonObject {
                    put(
                        "role",
                        JsonPrimitive(
                            when (m.role) {
                                ChatRole.User -> "user"
                                ChatRole.Assistant -> "assistant"
                            },
                        ),
                    )
                    put("blocks", encodeBlocks(m.blocks))
                },
            )
        }
    }

    private fun encodeBlocks(blocks: List<ContentBlock>): JsonArray = buildJsonArray {
        for (b in blocks) {
            add(
                when (b) {
                    is ContentBlock.Text -> buildJsonObject {
                        put("t", JsonPrimitive("text"))
                        put("text", JsonPrimitive(b.text))
                    }
                    is ContentBlock.ToolUse -> buildJsonObject {
                        put("t", JsonPrimitive("tool_use"))
                        put("id", JsonPrimitive(b.id))
                        put("name", JsonPrimitive(b.name))
                        put("input", b.input)
                    }
                    is ContentBlock.ToolResult -> buildJsonObject {
                        put("t", JsonPrimitive("tool_result"))
                        put("toolUseId", JsonPrimitive(b.toolUseId))
                        val savedContent =
                            if (b.images.isNotEmpty()) {
                                b.content + "\n[OpenDroid: 截图未写入会话文件，重开会话后需再次 capture_screenshot]"
                            } else {
                                b.content
                            }
                        put("content", JsonPrimitive(savedContent))
                        put("isError", JsonPrimitive(b.isError))
                    }
                },
            )
        }
    }

    private fun decodeConversation(arr: JsonArray): List<ChatMessage> = arr.map { el ->
        val o = el.jsonObject
        val role = when (o["role"]!!.jsonPrimitive.content) {
            "user" -> ChatRole.User
            else -> ChatRole.Assistant
        }
        val blocks = decodeBlocks(o["blocks"]?.jsonArray ?: buildJsonArray { })
        ChatMessage(role, blocks)
    }

    private fun decodeBlocks(arr: JsonArray): List<ContentBlock> = arr.map { el ->
        val o = el.jsonObject
        when (o["t"]!!.jsonPrimitive.content) {
            "text" -> ContentBlock.Text(o["text"]!!.jsonPrimitive.content)
            "tool_use" -> ContentBlock.ToolUse(
                id = o["id"]!!.jsonPrimitive.content,
                name = o["name"]!!.jsonPrimitive.content,
                input = o["input"]!!.jsonObject,
            )
            "tool_result" -> ContentBlock.ToolResult(
                toolUseId = o["toolUseId"]!!.jsonPrimitive.content,
                content = o["content"]!!.jsonPrimitive.content,
                isError = o["isError"]?.jsonPrimitive?.booleanOrNull == true,
            )
            else -> ContentBlock.Text("(未知块)")
        }
    }
}

fun deriveSessionTitle(lines: List<ChatLine>): String {
    val firstUser = lines.filterIsInstance<ChatLine.User>().firstOrNull()?.text?.trim().orEmpty()
    if (firstUser.isNotEmpty()) {
        return if (firstUser.length <= 28) firstUser else firstUser.take(28) + "…"
    }
    return ""
}
