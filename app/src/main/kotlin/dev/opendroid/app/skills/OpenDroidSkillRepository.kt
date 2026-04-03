package dev.opendroid.app.skills

import android.content.Context
import dev.opendroid.agent.SkillContentLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class OpenDroidSkillInfo(
    /** 目录名，传给 `load_skill.name` 必须用此 id */
    val skillId: String,
    val displayName: String,
    val description: String,
    val allowedTools: List<String>,
)

class OpenDroidSkillRepository(
    context: Context,
) : SkillContentLoader {
    private val root = File(context.filesDir, "skills").also { it.mkdirs() }

    fun ensureRoot() {
        root.mkdirs()
    }

    suspend fun listSkills(): List<OpenDroidSkillInfo> = withContext(Dispatchers.IO) {
        root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir ->
                val skillFile = File(dir, "SKILL.md")
                if (!skillFile.isFile) return@mapNotNull null
                runCatching {
                    val content = skillFile.readText(Charsets.UTF_8)
                    val fm = SkillFrontmatter.parse(content)
                    val id = dir.name
                    OpenDroidSkillInfo(
                        skillId = id,
                        displayName = fm["name"]?.takeIf { it.isNotBlank() } ?: id,
                        description = fm["description"]?.takeIf { it.isNotBlank() }
                            ?: "（可在 SKILL.md 的 YAML 头中添加 description）",
                        allowedTools = fm["allowed-tools"]
                            ?.split(" ", "\t")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?: emptyList(),
                    )
                }.getOrNull()
            }
            .orEmpty()
            .sortedBy { it.skillId.lowercase() }
    }

    suspend fun readSkillMarkdown(skillId: String): String? = withContext(Dispatchers.IO) {
      resolveFile(skillId)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    suspend fun saveSkillMarkdown(skillId: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val dir = OpenDroidSkillPaths.resolveSkillDir(root, skillId) ?: return@withContext false
        dir.mkdirs()
        val f = File(dir, "SKILL.md")
        f.writeText(content, Charsets.UTF_8)
        true
    }

    suspend fun deleteSkill(skillId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = OpenDroidSkillPaths.resolveSkillDir(root, skillId) ?: return@withContext false
        dir.deleteRecursively()
    }

    /**
     * 拼进 system prompt，让模型像 claude-code 一样先浏览再 `load_skill`。
     */
    fun catalogMarkdownBlock(skills: List<OpenDroidSkillInfo>, toolNames: List<String>): String {
        val toolsLine = toolNames.joinToString(", ")
        val header = buildString {
            appendLine("## 能力与资源（自动注入）")
            appendLine("可用设备工具名：`$toolsLine`。定义见 API tools 字段；需要时再调用。")
            appendLine()
            if (skills.isEmpty()) {
                appendLine("当前没有本地 Skill（可在侧栏「Skill 管理」中新建）。")
            } else {
                appendLine("本地 Skill：若用户任务与下述说明匹配，请先 `load_skill`，参数 `name` 为 **`skill_id`**（第一列），再按正文执行。")
                appendLine()
                for (s in skills) {
                    append("- **`${s.skillId}`** — ${s.displayName}：${s.description}")
                    if (s.allowedTools.isNotEmpty()) {
                        append(" （文档建议工具：${s.allowedTools.joinToString(", ")}）")
                    }
                    appendLine()
                }
                appendLine()
                appendLine("无匹配 Skill 时，直接使用工具与通用推理即可，不必强行 load_skill。")
            }
        }
        return header.trimEnd()
    }

    private fun resolveFile(skillId: String): File? {
        val dir = OpenDroidSkillPaths.resolveSkillDir(root, skillId) ?: return null
        return File(dir, "SKILL.md")
    }

    override suspend fun load(name: String): String? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        resolveFile(name.trim())?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }
}
