package com.orchords.orchordsai.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPackageValidatorTest {
    private fun skillFile(
        name: String = "sample-skill",
        description: String = "A useful sample skill",
        compatibility: String? = null,
        extraFrontmatter: String = "",
    ): ByteArray = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: $description")
        compatibility?.let { appendLine("compatibility: $it") }
        if (extraFrontmatter.isNotBlank()) appendLine(extraFrontmatter)
        appendLine("---")
        appendLine("Follow the requested workflow and report failures plainly.")
    }.toByteArray(Charsets.UTF_8)

    private fun files(
        root: ByteArray = skillFile(),
        vararg extras: Pair<String, ByteArray>,
    ): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        put("SKILL.md", root)
        extras.forEach { (path, content) -> put(path, content) }
    }

    @Test
    fun `valid package returns canonical metadata`() {
        val result = SkillPackageValidator.validate(
            "sample-skill",
            files(
                skillFile(extraFrontmatter = "disable-model-invocation: true"),
                "references/guide.md" to "guide".toByteArray(),
            ),
        )

        assertEquals("sample-skill", result.name)
        assertEquals("A useful sample skill", result.description)
        assertTrue(result.disableModelInvocation)
    }

    @Test
    fun `valid false or missing invocation policy keeps automatic invocation available`() {
        val missing = SkillPackageValidator.validate("sample-skill", files())
        val explicitFalse = SkillPackageValidator.validate(
            "sample-skill",
            files(skillFile(extraFrontmatter = "disable-model-invocation: false")),
        )

        assertFalse(missing.disableModelInvocation)
        assertFalse(explicitFalse.disableModelInvocation)
    }

    @Test
    fun `invalid or mismatched canonical names are rejected before activation`() {
        listOf(
            "Uppercase",
            "-leading",
            "trailing-",
            "double--hyphen",
            "under_score",
            "a".repeat(65),
        ).forEach { invalidName ->
            assertThrows(IllegalArgumentException::class.java) {
                SkillPackageValidator.validate(invalidName, files(skillFile(name = invalidName)))
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageValidator.validate("target-skill", files(skillFile(name = "different-skill")))
        }
    }

    @Test
    fun `required metadata and bounded compatibility are validated`() {
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageValidator.validate("sample-skill", files(skillFile(description = "\"\"")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageValidator.validate(
                "sample-skill",
                files(skillFile(compatibility = "x".repeat(501))),
            )
        }
    }

    @Test
    fun `present malformed invocation policy is rejected rather than silently installed`() {
        listOf("null", "\"\"", "truee", "1", "[]", "{}").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                SkillPackageValidator.validate(
                    "sample-skill",
                    files(skillFile(extraFrontmatter = "disable-model-invocation: $raw")),
                )
            }
        }
    }

    @Test
    fun `unsafe paths and package resource limit violations are rejected`() {
        listOf("../escape.txt", "/absolute.txt", "refs\\escape.txt", "refs/./escape.txt").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                SkillPackageValidator.validate(
                    "sample-skill",
                    files(skillFile(), path to byteArrayOf(1)),
                )
            }
        }

        val limits = SkillPackageLimits(maxFiles = 2, maxFileBytes = 256, maxTotalBytes = 300)
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageValidator.validate(
                "sample-skill",
                files(
                    skillFile(),
                    "a.txt" to byteArrayOf(1),
                    "b.txt" to byteArrayOf(1),
                ),
                limits,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageValidator.validate(
                "sample-skill",
                files(skillFile(), "large.txt" to ByteArray(257)),
                limits,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkillPackageValidator.validate(
                "sample-skill",
                files(skillFile(), "aggregate.txt" to ByteArray(250)),
                limits,
            )
        }
    }
}
