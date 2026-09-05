package com.orchords.orchordsai.data.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillImportReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun manifest(name: String = "example") = "---\nname: $name\ndescription: A useful skill\n---\nInstructions".toByteArray()
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    private fun read(bytes: ByteArray, name: String = "bundle.zip", limits: SkillImportLimits = SkillImportLimits()) =
        SkillImportReader.readLocal(ByteArrayInputStream(bytes), name, temp.root, limits)

    @Test fun `plain markdown remains bounded and preserves exact UTF8`() {
        val prepared = read(manifest(), "SKILL.md").single()
        assertEquals("example", prepared.name)
        assertTrue(prepared.preserveAssets)
        assertThrows(IllegalArgumentException::class.java) { read(ByteArray(MAX_SKILL_CONTENT_BYTES + 1), "SKILL.md") }
    }

    @Test fun `archive assets are binary and nested skill ownership stays separate`() {
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0)
        val packages = read(zip("outer/SKILL.md" to manifest(), "outer/asset.png" to image,
            "outer/inner/SKILL.md" to manifest("inner"), "outer/inner/ref.txt" to "ref".toByteArray()))
        assertEquals(setOf("example", "inner"), packages.map { it.name }.toSet())
        assertTrue(packages.none { it.preserveAssets })
        assertArrayEquals(image, packages.first { it.name == "example" }.files["asset.png"])
        assertFalse(packages.first { it.name == "example" }.files.keys.any { it.startsWith("inner/") })
        assertEquals(setOf("SKILL.md", "ref.txt"), packages.first { it.name == "inner" }.files.keys)
        assertTrue(temp.root.list()!!.isEmpty())
    }

    @Test fun `unsafe absolute traversal and ambiguous paths reject the entire archive`() {
        listOf("/SKILL.md", "../escape", "a/../escape", "a\\escape", "a//escape", "a/./escape", "C:/escape").forEach { unsafe ->
            assertThrows("path=$unsafe", IllegalArgumentException::class.java) {
                read(zip("SKILL.md" to manifest(), unsafe to byteArrayOf(1)))
            }
            assertTrue(temp.root.list()!!.isEmpty())
        }
    }

    @Test fun `duplicate case aliases and file-directory conflicts are not overwritten`() {
        listOf(
            zip("SKILL.md" to manifest(), "skill.md" to manifest()),
            zip("SKILL.md" to manifest(), "refs" to byteArrayOf(1), "refs/file.txt" to byteArrayOf(2)),
        ).forEach { bytes -> assertThrows(IllegalArgumentException::class.java) { read(bytes) } }
    }

    @Test fun `all packages are validated before any installation callback`() {
        var writes = 0
        val bytes = zip("a/SKILL.md" to manifest("first"), "z/SKILL.md" to "invalid".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            val prepared = read(bytes)
            installPreparedSkills(prepared) { writes++; true }
        }
        assertEquals(0, writes)
    }

    @Test fun `duplicate destination skill names in separate roots are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            read(zip("a/SKILL.md" to manifest(), "b/SKILL.md" to manifest()))
        }
    }

    @Test fun `compressed expanded per-file and entry limits are enforced`() {
        val limits = SkillImportLimits(maxArchiveBytes = 4096, maxEntries = 3,
            packageLimits = SkillPackageLimits(maxFiles = 3, maxFileBytes = 256, maxTotalBytes = 300))
        listOf(
            zip("SKILL.md" to manifest(), "large.txt" to ByteArray(257)),
            zip("SKILL.md" to manifest(), "a.txt" to ByteArray(200), "b.txt" to ByteArray(200)),
            zip("a/" to byteArrayOf(), "b/" to byteArrayOf(), "c/" to byteArrayOf(), "SKILL.md" to manifest()),
        ).forEach { bytes -> assertThrows(IllegalArgumentException::class.java) { read(bytes, limits = limits) } }
        assertThrows(IllegalArgumentException::class.java) {
            read(zip("SKILL.md" to manifest()), limits = limits.copy(maxArchiveBytes = 20))
        }
        assertTrue(temp.root.list()!!.isEmpty())
    }

    @Test fun `truncated archive is not treated as a complete import`() {
        val bytes = zip("SKILL.md" to manifest())
        assertThrows(java.util.zip.ZipException::class.java) { read(bytes.copyOf(bytes.size - 22)) }
    }

    @Test fun `cancellation closes acquisition stream and removes temporary archive`() {
        var closed = false
        val input = object : ByteArrayInputStream(zip("SKILL.md" to manifest(), "ref.txt" to ByteArray(20000))) {
            override fun close() { closed = true; super.close() }
        }
        var calls = 0
        assertThrows(CancellationException::class.java) {
            SkillImportReader.readLocal(input, "bundle.zip", temp.root) {
                if (++calls >= 5) throw CancellationException("cancelled")
            }
        }
        assertTrue(closed)
        assertTrue(temp.root.list()!!.isEmpty())
    }

    @Test fun `publication failure returns actual completed items instead of false total success`() {
        val prepared = read(zip("a/SKILL.md" to manifest("first"), "b/SKILL.md" to manifest("second")))
        val calls = mutableListOf<String>()
        val outcome = installPreparedSkills(prepared) { skill -> calls += skill.name; skill.name == "first" }
        assertEquals(listOf("first", "second"), calls)
        assertEquals(listOf("first"), outcome.installed)
        assertEquals("second", outcome.failed)
    }
}
