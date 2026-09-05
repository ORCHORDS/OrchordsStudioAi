package com.orchords.orchordsai.data.files

import java.io.File
import java.nio.file.Files

/** One serialized publication path. Crash recovery/version manifests remain separate lifecycle work. */
internal object SkillPackageStore {
    fun <T> withLock(block: () -> T): T = synchronized(this, block)

    fun replace(
        skillsRoot: File,
        skillName: String,
        files: Map<String, ByteArray>,
        limits: SkillPackageLimits = SkillPackageLimits(),
    ): Boolean = withLock {
        try {
            // Validate before copying caller-controlled buffers or creating directories.
            SkillPackageValidator.validate(skillName, files, limits)
            val snapshot = files.mapValues { it.value.copyOf() }
            SkillPackageValidator.validate(skillName, snapshot, limits)
            publish(skillsRoot, skillName, snapshot)
        } catch (_: Exception) {
            false
        }
    }

    /** Editing one file preserves all other package assets, including binary resources. */
    fun saveFile(
        skillsRoot: File,
        skillName: String,
        path: String,
        bytes: ByteArray,
        limits: SkillPackageLimits = SkillPackageLimits(),
    ): Boolean = withLock {
        try {
            SkillPackageValidator.requireName(skillName)
            SkillPackageValidator.requirePath(path)
            require(bytes.size <= limits.maxFileBytes) { "Skill file exceeds the byte limit" }
            val directory = skillsRoot.canonicalFile.resolve(skillName)
            requireNotLink(directory)
            val files = if (directory.exists()) readPackage(directory, limits) else linkedMapOf()
            files[path] = bytes
            replace(skillsRoot, skillName, files, limits)
        } catch (_: Exception) {
            false
        }
    }

    private fun readPackage(directory: File, limits: SkillPackageLimits): LinkedHashMap<String, ByteArray> {
        require(directory.isDirectory) { "Skill target is not a directory" }
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        var entries = 0L
        fun visit(folder: File, prefix: String) {
            Files.newDirectoryStream(folder.toPath()).use { children ->
                children.forEach { child ->
                    val file = child.toFile()
                    require(++entries <= limits.maxFiles.toLong() * 16) { "Skill has too many directory entries" }
                    val path = prefix + file.name
                    SkillPackageValidator.requirePath(path)
                    requireNotLink(file)
                    if (file.isDirectory) {
                        visit(file, "$path/")
                    } else {
                        require(file.isFile) { "Skill contains a non-regular file" }
                        require(files.size < limits.maxFiles) { "Skill package has too many files" }
                        val budget = minOf(limits.maxFileBytes.toLong(), limits.maxTotalBytes - total).toInt()
                        val bytes = file.inputStream().use { readBoundedSkillBytes(it, budget) }
                        total += bytes.size
                        files[path] = bytes
                    }
                }
            }
        }
        visit(directory, "")
        return files
    }

    private fun publish(skillsRoot: File, skillName: String, files: Map<String, ByteArray>): Boolean {
        val root = skillsRoot.canonicalFile
        require(root.isDirectory || root.mkdirs()) { "Cannot create skills directory" }
        val target = root.resolve(skillName)
        requireNotLink(target)
        require(!target.exists() || target.isDirectory) { "Skill target is not a directory" }
        // The private transaction has no root SKILL.md and cannot appear as an installed skill.
        val transaction = Files.createTempDirectory(root.toPath(), ".install-").toFile()
        val staging = transaction.resolve("staging")
        val backup = transaction.resolve("backup")
        var movedOld = false
        var published = false
        try {
            check(staging.mkdir()) { "Cannot create skill staging directory" }
            files.forEach { (path, bytes) ->
                val file = staging.resolve(path)
                val parent = requireNotNull(file.parentFile) { "Skill file has no parent directory" }
                check(parent.isDirectory || parent.mkdirs()) { "Cannot stage skill file" }
                requireNotLink(file)
                file.outputStream().use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
            }
            // Verify the actual staged frontmatter, not just the pre-write caller object.
            SkillPackageValidator.validateMetadata(skillName, readBoundedSkillText(staging.resolve("SKILL.md")))
            if (target.exists()) {
                check(target.renameTo(backup)) { "Cannot preserve previous skill" }
                movedOld = true
            }
            check(staging.renameTo(target)) { "Cannot activate staged skill" }
            published = true
            backup.deleteRecursively()
            return true
        } finally {
            if (!published && movedOld && !target.exists()) {
                // If rollback cannot complete, keep the only good copy for recovery.
                backup.renameTo(target)
            }
            if (!backup.exists()) transaction.deleteRecursively()
        }
    }

    private fun requireNotLink(file: File) {
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile) {
            "Symbolic links are not supported in skill packages"
        }
    }
}
