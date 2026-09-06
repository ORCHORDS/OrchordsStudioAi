package com.orchords.orchordsai.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.orchords.orchordsai.data.files.AgentSkillInstallCoordinator
import com.orchords.orchordsai.data.files.PreparedSkillPackage
import com.orchords.orchordsai.data.files.SkillInstallService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallToolsTest {
    private fun prepared() = PreparedSkillPackage(
        name = "sample-skill",
        files = mapOf(
            "SKILL.md" to "---\nname: sample-skill\ndescription: Safe helper\n---\nSENTINEL_SECRET_CONTENT\n".toByteArray(),
        ),
        sourceRevision = "0123456789abcdef0123456789abcdef01234567",
    )

    @Test
    fun `proposal tool is read-only and install tool always requires approval`() = runBlocking {
        var publications = 0
        val coordinator = AgentSkillInstallCoordinator(
            installer = SkillInstallService({ emptySet() }) { _, _ -> publications++; true },
            acquire = { _, _ -> prepared() },
            tokenFactory = { "proposal-token" },
        )
        val tools = buildSkillInstallTools(coordinator)
        val propose = tools.single { it.name == "propose_skill_install" }
        val install = tools.single { it.name == "install_skill" }

        assertFalse(propose.needsApproval(JsonObject(emptyMap())))
        assertTrue(install.needsApproval(JsonObject(emptyMap())))

        val proposalText = propose.execute(
            JsonObject(mapOf("source" to JsonPrimitive("https://github.com/ORCHORDS/example")))
        ).single().let { it as com.orchords.ai.ui.UIMessagePart.Text }.text
        val proposalJson = Json.parseToJsonElement(proposalText).jsonObject

        assertEquals("proposal_ready", proposalJson.getValue("status").jsonPrimitive.content)
        assertEquals("proposal-token", proposalJson.getValue("proposal_token").jsonPrimitive.content)
        assertEquals(0, publications)
        assertFalse(proposalText.contains("SENTINEL_SECRET_CONTENT"))
        assertTrue(proposalJson.getValue("installation_requires_user_approval").jsonPrimitive.content.toBoolean())
        assertFalse(proposalJson.getValue("activation_after_install").jsonPrimitive.content.toBoolean())

        val installText = install.execute(
            JsonObject(mapOf("proposal_token" to JsonPrimitive("proposal-token")))
        ).single().let { it as com.orchords.ai.ui.UIMessagePart.Text }.text
        val installJson = Json.parseToJsonElement(installText).jsonObject

        assertEquals("installed", installJson.getValue("status").jsonPrimitive.content)
        assertFalse(installJson.getValue("activated").jsonPrimitive.content.toBoolean())
        assertEquals(1, publications)
    }

    @Test
    fun `unknown install proposal performs zero writes`() = runBlocking {
        var publications = 0
        val coordinator = AgentSkillInstallCoordinator(
            installer = SkillInstallService({ emptySet() }) { _, _ -> publications++; true },
            acquire = { _, _ -> prepared() },
        )
        val install = buildSkillInstallTools(coordinator).single { it.name == "install_skill" }

        val output = install.execute(
            JsonObject(mapOf("proposal_token" to JsonPrimitive("unknown")))
        ).single().let { it as com.orchords.ai.ui.UIMessagePart.Text }.text

        assertTrue(output.contains("unknown_or_expired_proposal"))
        assertEquals(0, publications)
    }
}
