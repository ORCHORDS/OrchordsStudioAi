package com.orchords.orchordsai.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSkillInstallCoordinatorTest {
    private fun skill(extraFiles: Int = 0) = PreparedSkillPackage(
        name = "sample-skill",
        files = buildMap {
            put(
                "SKILL.md",
                "---\nname: sample-skill\ndescription: Useful sample\ncompatibility: OrchordsAI\ndisable-model-invocation: true\n---\nSENTINEL_PRIVATE_BODY\n".toByteArray(),
            )
            repeat(extraFiles) { index -> put("refs/$index.txt", "reference-$index".toByteArray()) }
        },
        sourceRevision = "0123456789abcdef0123456789abcdef01234567",
    )

    @Test
    fun `proposal is inspectable but performs zero publication until one-shot install`() {
        var publications = 0
        val installer = SkillInstallService(
            existingSkillNames = { setOf("sample-skill") },
            installPackage = { _, _ -> publications++; true },
        )
        val coordinator = AgentSkillInstallCoordinator(
            installer = installer,
            acquire = { _, _ -> skill() },
            tokenFactory = { "proposal-1" },
        )

        val proposal = coordinator.propose("https://github.com/ORCHORDS/example")

        assertEquals(0, publications)
        assertEquals("proposal-1", proposal.proposalToken)
        assertEquals("sample-skill", proposal.name)
        assertEquals("Useful sample", proposal.description)
        assertEquals("OrchordsAI", proposal.compatibility)
        assertTrue(proposal.disableModelInvocation)
        assertTrue(proposal.replacesExisting)
        assertEquals(1, proposal.fileCount)
        assertFalse(proposal.fileNames.joinToString().contains("SENTINEL_PRIVATE_BODY"))

        val installed = coordinator.install(proposal.proposalToken)
        assertTrue(requireNotNull(installed).installed)
        assertEquals(1, publications)
        assertNull(coordinator.install(proposal.proposalToken))
        assertEquals(1, publications)
    }

    @Test
    fun `expired proposal cannot install`() {
        var now = 1_000L
        var publications = 0
        val coordinator = AgentSkillInstallCoordinator(
            installer = SkillInstallService({ emptySet() }) { _, _ -> publications++; true },
            acquire = { _, _ -> skill() },
            nowMillis = { now },
            tokenFactory = { "expires" },
        )
        val proposal = coordinator.propose("https://github.com/ORCHORDS/example")

        now += 15L * 60L * 1000L + 1L

        assertNull(coordinator.install(proposal.proposalToken))
        assertEquals(0, publications)
        assertEquals(0, coordinator.pendingCount())
    }

    @Test
    fun `proposal file preview is bounded and reports omitted files`() {
        val coordinator = AgentSkillInstallCoordinator(
            installer = SkillInstallService({ emptySet() }) { _, _ -> true },
            acquire = { _, _ -> skill(extraFiles = 40) },
            tokenFactory = { "many-files" },
        )

        val proposal = coordinator.propose("https://github.com/ORCHORDS/example")

        assertTrue(proposal.fileCount > proposal.fileNames.size)
        assertTrue(proposal.fileNames.size <= 32)
        assertEquals(proposal.fileCount - proposal.fileNames.size, proposal.omittedFileCount)
        assertTrue(proposal.fileNames.all { it.length <= 160 })
    }

    @Test
    fun `reserved provenance path is rejected before publication`() {
        var publications = 0
        val prepared = skill().copy(
            files = skill().files + (SKILL_INSTALL_PROVENANCE_PATH to "attacker".toByteArray()),
        )
        val service = SkillInstallService({ emptySet() }) { _, _ -> publications++; true }

        val result = runCatching { service.propose("https://github.com/ORCHORDS/example", prepared) }

        assertTrue(result.isFailure)
        assertEquals(0, publications)
    }
}
