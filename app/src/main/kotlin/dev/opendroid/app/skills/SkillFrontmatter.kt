package dev.opendroid.app.skills

object SkillFrontmatter {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!content.startsWith("---")) return result
        val endRange = findFrontmatterEndRange(content) ?: return result
        val yaml = content.substring(3, endRange.first).trim()
        for (line in yaml.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim().removeSurrounding("\"")
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                }
            }
        }
        return result
    }

    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }
}
