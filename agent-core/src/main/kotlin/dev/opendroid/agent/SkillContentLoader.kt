package dev.opendroid.agent

fun interface SkillContentLoader {
    suspend fun load(name: String): String?
}
