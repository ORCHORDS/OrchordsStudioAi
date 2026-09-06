package com.orchords.orchordsai.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallServiceTest {
    private val skill = PreparedSkillPackage(
        name = "sample-skill",
        files = mapOf(
            "SKILL.md" to "---\nname: sample-skill\ndescription: Useful sample\n---\nDo useful work.\n".toByteArray(),
            "reference.txt" to "bounded reference".toByteArray(),
        ),
        sourceRevision = "0123456789abcdef0123456789abcdef01234567",
    )

    @Test
    fun `proposal exposes bounded metadata without installing`() {
        var installs = 0
        val service = SkillInstallService(
            existingSkillNames = { setOf("sample-skill") },
            installPackage = { installs++; true },
        )

        val proposal = service.propose(
            source = "github:ORCHORDS/example@0123456789abcdef0123456789abcdef01234567",
            prepared = skill,
        )

        assertEquals("sample-skill", proposal.name)
        assertEquals(2, proposal.fileCount)
        assertEquals(skill.files.values.sumOf { it.size.toLong() }, proposal.totalBytes)
        assertEquals(skill.sourceRevision, proposal.sourceRevision)
        assertTrue(proposal.replacesExisting)
        assertTrue(proposal.source.length <= 240)
        assertEquals(0, installs)
    }

    @Test
    fun `install is a separate operation with structured result`() {
        var installs = 0
        val service = SkillInstallService(
            existingSkillNames = { emptySet() },
            installPackage = { installs++; true },
        )
        val proposal = service.propose("local:selected-file", skill)

        val result = service.install(proposal)

        assertTrue(result.installed)
        assertFalse(result.replacedExisting)
        assertEquals("sample-skill", result.name)
        assertEquals(skill.sourceRevision, result.sourceRevision)
        assertEquals(1, installs)
    }

    @Test
    fun `failed publication remains a bounded failed result`() {
        val service = SkillInstallService(
            existingSkillNames = { setOf("sample-skill") },
            installPackage = { false },
        )
        val proposal = service.propose("local:selected-file", skill)

        val result = service.install(proposal)

        assertFalse(result.installed)
        assertTrue(result.replacedExisting)
        assertEquals("sample-skill", result.name)
    }
}
