package com.orchords.orchordsai.data.files

import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal data class SkillImportLimits(
    val maxArchiveBytes: Int = 8 * 1024 * 1024,
    val maxEntries: Int = 1024,
    val packageLimits: SkillPackageLimits = SkillPackageLimits(),
) {
    init { require(maxArchiveBytes > 0 && maxEntries > 0) }
}

internal data class PreparedSkillPackage(val name: String, val files: Map<String, ByteArray>, val sourceRevision: String? = null, val preserveAssets: Boolean = false)
internal data class SkillImportOutcome(val installed: List<String>, val failed: String?)

/** Preflight is complete before publication begins. IO failure reports completed packages explicitly. */
internal fun installPreparedSkills(
    packages: List<PreparedSkillPackage>,
    install: (PreparedSkillPackage) -> Boolean,
): SkillImportOutcome {
    require(packages.isNotEmpty()) { "No skills were prepared" }
    packages.forEach { SkillPackageValidator.validate(it.name, it.files) }
    require(packages.map { it.name }.distinct().size == packages.size) { "Duplicate skill destinations" }
    val installed = mutableListOf<String>()
    for (skill in packages) {
        if (!install(skill)) return SkillImportOutcome(installed.toList(), skill.name)
        installed += skill.name
    }
    return SkillImportOutcome(installed.toList(), null)
}

/** Bounded, cancellation-aware acquisition; owns and closes the supplied input stream. */
internal object SkillImportReader {
    fun readLocal(
        input: InputStream,
        fileName: String,
        temporaryDirectory: File,
        limits: SkillImportLimits = SkillImportLimits(),
        checkpoint: () -> Unit = {},
    ): List<PreparedSkillPackage> = input.use { original ->
        val source = PushbackInputStream(original, 4)
        val header = ByteArray(4)
        var size = 0
        while (size < header.size) {
            checkpoint()
            val next = source.read()
            if (next < 0) break
            header[size++] = next.toByte()
        }
        if (size > 0) source.unread(header, 0, size)
        val archive = fileName.endsWith(".zip", ignoreCase = true) ||
            (size == 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
                ((header[2] == 3.toByte() && header[3] == 4.toByte()) ||
                    (header[2] == 5.toByte() && header[3] == 6.toByte()) ||
                    (header[2] == 7.toByte() && header[3] == 8.toByte())))
        if (!archive) {
            val bytes = readBoundedSkillBytes(SkillAcquisitionInput(source, MAX_SKILL_CONTENT_BYTES.toLong(), checkpoint), MAX_SKILL_CONTENT_BYTES)
            return@use listOf(prepareSingle(mapOf("SKILL.md" to bytes), limits.packageLimits).copy(preserveAssets = true))
        }
        require(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) { "Cannot prepare temporary skill import" }
        val temporary = Files.createTempFile(temporaryDirectory.toPath(), ".skill-import-", ".zip").toFile()
        try {
            temporary.outputStream().use { output ->
                SkillAcquisitionInput(source, limits.maxArchiveBytes.toLong(), checkpoint).copyTo(output)
            }
            checkpoint()
            // ZipFile validates the central directory; a truncated local-entry stream is not success.
            ZipFile(temporary).use { zip ->
                require(zip.size() in 1..limits.maxEntries) { "Skill archive has too many entries or is empty" }
                val paths = HashSet<String>()
                val files = linkedMapOf<String, ByteArray>()
                var total = 0L
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkpoint()
                    val entry = entries.nextElement()
                    val path = if (entry.isDirectory) entry.name.removeSuffix("/") else entry.name
                    SkillPackageValidator.requirePath(path)
                    require(paths.add(path.lowercase(Locale.ROOT))) { "Skill archive contains colliding paths" }
                    val remaining = limits.packageLimits.maxTotalBytes - total
                    val perFile = if (path.substringAfterLast('/').equals("SKILL.md", true))
                        minOf(MAX_SKILL_CONTENT_BYTES, limits.packageLimits.maxFileBytes) else limits.packageLimits.maxFileBytes
                    val budget = if (entry.isDirectory) 0 else minOf(perFile.toLong(), remaining).toInt()
                    require(entry.size in 0..budget.toLong()) { "Skill archive entry exceeds the byte limit" }
                    if (!entry.isDirectory) require(files.size < limits.packageLimits.maxFiles) { "Skill archive has too many files" }
                    val bytes = zip.getInputStream(entry).use { content ->
                        readBoundedSkillBytes(SkillAcquisitionInput(content, budget.toLong(), checkpoint), budget)
                    }
                    require(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc) {
                        "Skill archive entry failed its integrity check"
                    }
                    if (!entry.isDirectory) { total += bytes.size; files[path] = bytes }
                }
                prepareArchive(files, limits.packageLimits, checkpoint)
            }
        } finally { temporary.delete() }
    }

    fun prepareSingle(files: Map<String, ByteArray>, limits: SkillPackageLimits = SkillPackageLimits()): PreparedSkillPackage {
        val source = decodeSkillText(requireNotNull(files["SKILL.md"]) { "SKILL.md is required" })
        val name = requireNotNull(SkillFrontmatterParser.parse(source)["name"]) { "Skill name is required" }
        SkillPackageValidator.validate(name, files, limits)
        return PreparedSkillPackage(name, files)
    }

    private fun prepareArchive(
        files: Map<String, ByteArray>, limits: SkillPackageLimits, checkpoint: () -> Unit,
    ): List<PreparedSkillPackage> {
        val roots = files.keys.filter { it.substringAfterLast('/').equals("SKILL.md", true) }
            .map { it.substringBeforeLast('/', "") }.sorted()
        require(roots.isNotEmpty()) { "SKILL.md not found in the archive" }
        val packages = roots.map { root ->
            checkpoint()
            val owned = linkedMapOf<String, ByteArray>()
            files.forEach { (path, bytes) ->
                // The deepest matching root owns a resource; nested packages do not leak into parents.
                val owner = roots.filter { it.isEmpty() || path.startsWith("$it/") }.maxByOrNull { it.length }
                if (owner == root) {
                    val relative = if (root.isEmpty()) path else path.removePrefix("$root/")
                    owned[if (relative.equals("SKILL.md", true)) "SKILL.md" else relative] = bytes
                }
            }
            prepareSingle(owned, limits)
        }
        require(packages.map { it.name }.distinct().size == packages.size) { "Duplicate skill destinations" }
        return packages
    }
}

/** Limits bytes at the source, before a caller can allocate an unbounded response or decompression. */
internal class SkillAcquisitionInput(
    private val source: InputStream,
    private val limit: Long,
    private val checkpoint: () -> Unit = {},
) : InputStream() {
    private var consumed = 0L
    init { require(limit >= 0) }
    override fun read(): Int {
        checkpoint()
        val value = source.read()
        if (value >= 0) { consumed++; require(consumed <= limit) { "Skill acquisition exceeds the byte limit" } }
        checkpoint()
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        checkpoint()
        val count = source.read(buffer, offset, minOf(length.toLong(), limit - consumed + 1).toInt())
        if (count == 0) {
            val value = read()
            if (value < 0) return -1
            buffer[offset] = value.toByte()
            return 1
        }
        if (count > 0) { consumed += count; require(consumed <= limit) { "Skill acquisition exceeds the byte limit" } }
        checkpoint()
        return count
    }
    override fun close() = source.close()
}
