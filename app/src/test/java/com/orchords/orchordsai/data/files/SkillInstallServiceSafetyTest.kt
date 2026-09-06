package com.orchords.orchordsai.data.files

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallServiceSafetyTest {
    private fun skill(): PreparedSkillPackage = PreparedSkillPackage(
        name = "sample-skill",
        files = mapOf(
            "SKILL.md" to "---\nname: sample-skill\ndescription: Useful sample\n---\nOriginal body.\n".toByteArray(),
        ),
        sourceRevision = "0123456789abcdef0123456789abcdef01234567",
        preserveAssets = true,
    )

    @Test
    fun `publication uses the proposed bytes not subsequently mutated caller bytes`() {
        val prepared = skill()
        val expected = prepared.files.getValue("SKILL.md").toList()
        var published: PreparedSkillPackage? = null
        val service = SkillInstallService({ emptySet() }) { published = it; true }
        val proposal = service.propose("local:selected-file", prepared)
        prepared.files.getValue("SKILL.md").fill(0)
        assertTrue(service.install(proposal).installed)
        assertEquals(expected, published!!.files.getValue("SKILL.md").toList())
        assertTrue(published!!.preserveAssets)
    }

    @Test
    fun `a proposal can publish only once`() {
        var publications = 0
        val service = SkillInstallService({ emptySet() }) { publications++; true }
        val proposal = service.propose("local", skill())
        assertTrue(service.install(proposal).installed)
        assertFalse(service.install(proposal).installed)
        assertEquals(1, publications)
    }

    @Test
    fun `a different service cannot consume a proposal`() {
        var publications = 0
        val first = SkillInstallService({ emptySet() }) { publications++; true }
        val second = SkillInstallService({ emptySet() }) { publications++; true }
        val proposal = first.propose("local", skill())
        assertFalse(second.install(proposal).installed)
        assertEquals(0, publications)
        assertTrue(first.install(proposal).installed)
        assertEquals(1, publications)
    }

    @Test
    fun `a newly appeared destination requires a fresh proposal`() {
        var existing = emptySet<String>()
        var publications = 0
        val service = SkillInstallService({ existing }) { publications++; true }
        val proposal = service.propose("local", skill())
        existing = setOf("sample-skill")
        val result = service.install(proposal)
        assertFalse(result.installed)
        assertEquals("destination_changed", result.failure)
        assertEquals(0, publications)
    }

    @Test
    fun `publication exceptions return bounded failure without private exception text`() {
        val service = SkillInstallService({ emptySet() }) { throw IllegalStateException("private payload") }
        val result = service.install(service.propose("local", skill()))
        assertFalse(result.installed)
        assertEquals("publication_failed", result.failure)
        assertFalse(result.toString().contains("private payload"))
    }

    @Test(expected = CancellationException::class)
    fun `publication cancellation propagates`() {
        val service = SkillInstallService({ emptySet() }) { throw CancellationException("cancel") }
        service.install(service.propose("local", skill()))
    }

    @Test
    fun `proposal source metadata is bounded and excludes control characters`() {
        val service = SkillInstallService({ emptySet() }) { true }
        val proposal = service.propose("\n\r\t" + "x".repeat(500), skill())
        assertEquals(240, proposal.source.length)
        assertTrue(proposal.source.none { it.isISOControl() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid packages cannot create a proposal`() {
        val service = SkillInstallService({ emptySet() }) { error("must not publish invalid package") }
        service.propose("local", skill().copy(name = "../escape"))
    }
}
