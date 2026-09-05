package com.orchords.orchordsai.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.orchords.orchordsai.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
    }

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listSkills(): List<SkillMetadata> = SkillPackageStore.withLock {
        getSkillsDir().listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir)
            }
            ?: emptyList()
    }

    fun readSkillBody(skillName: String): String? =
        readSkillContent(skillName)?.let(SkillFrontmatterParser::extractBody)

    fun readSkillContent(skillName: String): String? = SkillPackageStore.withLock {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return@withLock null
        runCatching { readBoundedSkillText(skillFile) }.getOrNull()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? = SkillPackageStore.withLock {
        if (!saveSkillFile(name, "SKILL.md", content)) return@withLock null
        val skillDir = resolveSkillDir(name) ?: return@withLock null
        parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        val deleted = SkillPackageStore.withLock { skillDir.deleteRecursively() }
        if (deleted) {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    /**
     *
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val directories = SkillPackageStore.withLock { getSkillsDir().listFiles() }
            ?: return@withContext skills
        val existing = skills.mapTo(HashSet()) { it.name }
        directories.filter { it.isDirectory && !it.name.startsWith('.') }.forEach { existing.add(it.name) }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val limits = SkillPackageLimits()
        if (content.length > limits.maxFileBytes) return false
        return SkillPackageStore.saveFile(
            context.filesDir.resolve(FileFolders.SKILLS), skillName, relativePath,
            content.toByteArray(Charsets.UTF_8), limits,
        )
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        val limits = SkillPackageLimits()
        if (files.size > limits.maxFiles || files.values.any { it.length > limits.maxFileBytes } ||
            files.values.sumOf { it.length.toLong() } > limits.maxTotalBytes) return false
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray(Charsets.UTF_8) },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean =
        SkillPackageStore.replace(context.filesDir.resolve(FileFolders.SKILLS), skillName, files)

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean = SkillPackageStore.withLock {
        val skillDir = resolveSkillDir(skillName) ?: return@withLock false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return@withLock false
        target.delete()
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? {
        return runCatching {
            val content = readBoundedSkillText(skillFile)
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                disableModelInvocation = frontmatter.isModelInvocationDisabled(),
                skillDir = skillDir,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val disableModelInvocation: Boolean = false,
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
