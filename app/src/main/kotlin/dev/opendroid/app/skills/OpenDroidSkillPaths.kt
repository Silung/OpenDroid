package dev.opendroid.app.skills

import java.io.File

internal object OpenDroidSkillPaths {
    fun isValidSkillId(skillId: String): Boolean {
        if (skillId.isBlank() || skillId == "." || skillId == "..") return false
        if (skillId.contains('/') || skillId.contains('\\')) return false
        if (!skillId.matches(Regex("""[a-zA-Z0-9._-]+"""))) return false
        return true
    }

    fun resolveSkillDir(skillsRoot: File, skillId: String): File? {
        if (skillId.isBlank()) return null
        if (skillId == "." || skillId == "..") return null
        if (skillId.contains('/') || skillId.contains('\\')) return null

        val canonicalRoot = skillsRoot.canonicalFile
        val canonicalDir = canonicalRoot.resolve(skillId).canonicalFile
        val parent = canonicalDir.parentFile ?: return null

        if (parent != canonicalRoot) return null
        if (!canonicalDir.isSameOrInside(canonicalRoot)) return null

        return canonicalDir
    }

    private fun File.isSameOrInside(root: File): Boolean {
        val rootPath = root.canonicalFile.path
        val currentPath = canonicalFile.path
        return currentPath == rootPath || currentPath.startsWith(rootPath + File.separator)
    }
}
