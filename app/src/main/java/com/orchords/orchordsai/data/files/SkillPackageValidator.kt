package com.orchords.orchordsai.data.files

import java.util.Locale

/** Product acquisition limits, not vendor promises or an execution grant. */
internal data class SkillPackageLimits(
    val maxFiles: Int = 256,
    val maxFileBytes: Int = 4 * 1024 * 1024,
    val maxTotalBytes: Long = 16L * 1024 * 1024,
) {
    init {
        require(maxFiles > 0 && maxFileBytes in 1 until Int.MAX_VALUE && maxTotalBytes > 0)
    }
}

internal data class SkillPackageMetadata(
    val name: String,
    val description: String,
    val compatibility: String?,
    val disableModelInvocation: Boolean,
)

/** Validates the exact package that will be published, before any live file changes. */
internal object SkillPackageValidator {
    private val canonicalName = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val compiledExtensions = setOf("dex", "jar", "class", "so", "dll", "exe", "apk", "aab")

    fun requireName(name: String) {
        require(name.length in 1..64 && canonicalName.matches(name)) { "Invalid skill directory name" }
    }

    fun requirePath(path: String) {
        require(path.length in 1..1024 && path.none { it.isISOControl() || it == '\\' || it == ':' }) {
            "Invalid skill file path"
        }
        val parts = path.split('/')
        require(parts.size <= 16 && parts.all { it.isNotBlank() && it != "." && it != ".." && it == it.trim() }) {
            "Skill file paths must be relative and canonical"
        }
    }

    fun validate(
        skillName: String,
        files: Map<String, ByteArray>,
        limits: SkillPackageLimits = SkillPackageLimits(),
    ): SkillPackageMetadata {
        requireName(skillName)
        require(files.size in 1..limits.maxFiles) { "Skill package has too many files or is empty" }
        val paths = HashSet<String>()
        var total = 0L
        files.forEach { (path, bytes) ->
            requirePath(path)
            val folded = path.lowercase(Locale.ROOT)
            require(paths.add(folded)) { "Skill file paths collide" }
            require(bytes.size <= limits.maxFileBytes) { "Skill file exceeds the byte limit" }
            require(bytes.size.toLong() <= limits.maxTotalBytes - total) { "Skill package exceeds the total byte limit" }
            total += bytes.size
            require(path.substringAfterLast('.', "").lowercase(Locale.ROOT) !in compiledExtensions &&
                !hasCompiledHeader(bytes)) { "Compiled executable content is not supported in skill packages" }
        }
        paths.forEach { path ->
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent !in paths) { "A skill file cannot also be a directory" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        val source = decodeSkillText(requireNotNull(files["SKILL.md"]) { "SKILL.md is required" })
        return validateMetadata(skillName, source)
    }

    fun validateMetadata(skillName: String, source: String): SkillPackageMetadata {
        requireName(skillName)
        require(source.startsWith("---\n") || source.startsWith("---\r\n")) { "SKILL.md requires YAML frontmatter" }
        val metadata = SkillFrontmatterParser.parse(source)
        val name = metadata["name"]
        require(name == skillName) { "Skill name must match its directory" }
        val description = metadata["description"]
        require(!description.isNullOrBlank() && description.length <= 1024) { "Invalid skill description" }
        val compatibility = metadata["compatibility"]
        require(!metadata.contains("compatibility") ||
            (!compatibility.isNullOrBlank() && compatibility.length <= 500)) { "Invalid skill compatibility" }
        require(!metadata.contains("disable-model-invocation") ||
            metadata.getBoolean("disable-model-invocation") != null) { "Invalid model invocation policy" }
        // Unknown extension fields remain inert. Nothing in frontmatter grants tool/account access.
        return SkillPackageMetadata(skillName, description, compatibility, metadata.isModelInvocationDisabled())
    }

    private fun hasCompiledHeader(bytes: ByteArray): Boolean {
        fun starts(vararg signature: Int): Boolean = bytes.size >= signature.size &&
            signature.indices.all { (bytes[it].toInt() and 255) == signature[it] }
        return starts(0x7f, 0x45, 0x4c, 0x46) || starts(0x4d, 0x5a) ||
            starts(0xca, 0xfe, 0xba, 0xbe) || starts(0x64, 0x65, 0x78, 0x0a)
    }
}
