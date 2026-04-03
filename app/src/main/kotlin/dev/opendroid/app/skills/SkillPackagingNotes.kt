package dev.opendroid.app.skills

/**
 * 本地 Skill 存储在 `context.filesDir/skills/<skill_id>/SKILL.md`，由 [OpenDroidSkillRepository] 读取；
 * Agent 通过 `load_skill`（参数 name = skill_id）拉取全文。侧栏「Skill 管理」可新建/编辑。
 */
object SkillPackagingNotes
