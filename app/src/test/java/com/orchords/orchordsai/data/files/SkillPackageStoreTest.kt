package com.orchords.orchordsai.data.files

import com.orchords.orchordsai.data.extensions.BuiltInLibrary
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillPackageStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun root() = temp.newFolder()
    private fun manifest(body: String = "original") =
        "---\nname: example\ndescription: A useful example\n---\n$body".toByteArray()
    private fun snapshot(root: File): Map<String, List<Byte>> = root.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }

    @Test fun `invalid metadata cannot replace the previous live package`() {
        val root = root()
        val files = linkedMapOf("SKILL.md" to manifest(), "assets/image.png" to byteArrayOf(1, 2, 3))
        assertTrue(SkillPackageStore.replace(root, "example", files))
        val before = snapshot(root)
        listOf(
            "not frontmatter",
            "---\nname: example\n---\nmissing description",
            "---\nname: different\ndescription: changed\n---\nwrong identity",
            "---\nname: example\ndescription: changed\ndisable-model-invocation: null\n---\ninvalid policy",
        ).forEach { source ->
            assertFalse(SkillPackageStore.replace(root, "example", mapOf("SKILL.md" to source.toByteArray())))
            assertEquals(before, snapshot(root))
        }
    }

    @Test fun `editing root instructions preserves referenced binary assets`() {
        val root = root()
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 1)
        assertTrue(SkillPackageStore.replace(root, "example", mapOf("SKILL.md" to manifest(), "assets/image.png" to image)))
        assertTrue(SkillPackageStore.saveFile(root, "example", "SKILL.md", manifest("edited")))
        assertArrayEquals(image, root.resolve("example/assets/image.png").readBytes())
        assertEquals("edited", SkillFrontmatterParser.extractBody(root.resolve("example/SKILL.md").readText()))
        assertEquals(listOf("example"), root.list()!!.toList())
    }

    @Test fun `single file editor cannot bypass root validation`() {
        val root = root()
        assertTrue(SkillPackageStore.replace(root, "example", mapOf("SKILL.md" to manifest())))
        val before = snapshot(root)
        assertFalse(SkillPackageStore.saveFile(root, "example", "SKILL.md", "invalid".toByteArray()))
        assertFalse(SkillPackageStore.saveFile(root, "example", "../escape", byteArrayOf(1)))
        assertEquals(before, snapshot(root))
    }

    @Test fun `resource bounds and compiled packages leave existing content intact`() {
        val root = root()
        assertTrue(SkillPackageStore.replace(root, "example", mapOf("SKILL.md" to manifest())))
        val before = snapshot(root)
        val limits = SkillPackageLimits(maxFiles = 2, maxFileBytes = 256, maxTotalBytes = 300)
        assertFalse(SkillPackageStore.replace(root, "example", mapOf("SKILL.md" to manifest(), "extra" to ByteArray(257)), limits))
        assertFalse(SkillPackageStore.replace(root, "example", mapOf("SKILL.md" to manifest(), "plugin.dex" to byteArrayOf(1))))
        assertFalse(SkillPackageStore.replace(root, "example", mapOf("SKILL.md" to manifest(), "renamed.bin" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46))))
        assertEquals(before, snapshot(root))
    }

    @Test fun `malformed UTF8 and oversized root never become live`() {
        val root = root()
        listOf(byteArrayOf(0xc3.toByte(), 0x28), manifest() + byteArrayOf(0), ByteArray(MAX_SKILL_CONTENT_BYTES + 1)).forEach { bytes ->
            assertFalse(SkillPackageStore.replace(root, "example", mapOf("SKILL.md" to bytes)))
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test fun `case and file directory collisions fail before publication`() {
        val root = root()
        listOf(
            mapOf("SKILL.md" to manifest(), "skill.md" to manifest()),
            mapOf("SKILL.md" to manifest(), "refs" to byteArrayOf(1), "Refs/guide.md" to byteArrayOf(2)),
        ).forEach { files ->
            assertFalse(SkillPackageStore.replace(root, "example", files))
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test fun `symlink targets and existing symlink resources are not followed`() {
        val root = root()
        val outside = temp.newFolder()
        outside.resolve("sentinel").writeText("keep")
        Files.createSymbolicLink(root.resolve("example").toPath(), outside.toPath())
        assertFalse(SkillPackageStore.replace(root, "example", mapOf("SKILL.md" to manifest())))
        assertEquals("keep", outside.resolve("sentinel").readText())
        assertFalse(outside.resolve("SKILL.md").exists())
        Files.delete(root.resolve("example").toPath())
        assertTrue(SkillPackageStore.replace(root, "example", mapOf("SKILL.md" to manifest())))
        Files.createSymbolicLink(root.resolve("example/link").toPath(), outside.resolve("sentinel").toPath())
        assertFalse(SkillPackageStore.saveFile(root, "example", "SKILL.md", manifest("edited")))
        assertEquals("original", SkillFrontmatterParser.extractBody(root.resolve("example/SKILL.md").readText()))
        assertEquals("keep", outside.resolve("sentinel").readText())
    }

    @Test fun `every shipped built in skill satisfies the same package validator`() {
        BuiltInLibrary.catalog.skills.forEach { skill ->
            val metadata = SkillPackageValidator.validate(skill.name, mapOf("SKILL.md" to skill.skillFile().toByteArray()))
            assertEquals(skill.name, metadata.name)
            assertEquals(skill.description, metadata.description)
        }
    }

    @Test fun `invalid new package does not create its target`() {
        val root = temp.root.resolve("not-created")
        assertFalse(SkillPackageStore.replace(root, "example", mapOf("readme.md" to byteArrayOf(1))))
        assertFalse(root.exists())
    }
}
