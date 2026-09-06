package com.orchords.orchordsai.data.ai.tools.local

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.orchords.ai.core.InputSchema
import com.orchords.ai.core.Tool
import com.orchords.ai.ui.UIMessagePart
import com.orchords.orchordsai.data.files.AgentSkillInstallCoordinator

internal fun buildSkillInstallTools(coordinator: AgentSkillInstallCoordinator): List<Tool> = listOf(
    Tool(
        name = "propose_skill_install",
        description = "Download and validate a public GitHub skill package without installing it. Returns bounded source, compatibility, overwrite and file-summary metadata plus a short-lived proposal token. Use install_skill only after this proposal is visible to the user.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("source", buildJsonObject {
                        put("type", "string")
                        put("description", "HTTPS github.com repository or tree URL for one skill package")
                    })
                },
                required = listOf("source"),
            )
        },
        execute = { input ->
            val source = input.jsonObject["source"]?.jsonPrimitive?.content?.trim().orEmpty()
            val coroutineContext = currentCoroutineContext()
            val output = runCatching {
                require(source.isNotBlank()) { "source is required" }
                val proposal = coordinator.propose(source) { coroutineContext.ensureActive() }
                buildJsonObject {
                    put("status", "proposal_ready")
                    put("proposal_token", proposal.proposalToken)
                    put("source", proposal.source)
                    put("name", proposal.name)
                    put("description", proposal.description)
                    proposal.compatibility?.let { put("compatibility", it) }
                    put("disable_model_invocation", proposal.disableModelInvocation)
                    proposal.sourceRevision?.let { put("source_revision", it) }
                    put("replaces_existing", proposal.replacesExisting)
                    put("file_count", proposal.fileCount)
                    put("total_bytes", proposal.totalBytes)
                    put("files", buildJsonArray { proposal.fileNames.forEach { add(it) } })
                    put("omitted_file_count", proposal.omittedFileCount)
                    put("installation_requires_user_approval", true)
                    put("activation_after_install", false)
                }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                buildJsonObject {
                    put("status", "proposal_failed")
                    put("error", "Skill proposal failed; check the public GitHub source and package limits.")
                }
            }
            listOf(UIMessagePart.Text(output.toString()))
        },
    ),
    Tool(
        name = "install_skill",
        description = "Install exactly one previously validated skill proposal. This write always requires user approval, consumes the proposal token once, and never enables the installed skill for any assistant.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("proposal_token", buildJsonObject {
                        put("type", "string")
                        put("description", "Short-lived token returned by propose_skill_install")
                    })
                },
                required = listOf("proposal_token"),
            )
        },
        needsApproval = { true },
        execute = { input ->
            val token = input.jsonObject["proposal_token"]?.jsonPrimitive?.content?.trim().orEmpty()
            val result = token.takeIf { it.isNotBlank() }?.let(coordinator::install)
            val output = if (result == null) {
                buildJsonObject {
                    put("status", "not_installed")
                    put("failure", "unknown_or_expired_proposal")
                }
            } else {
                buildJsonObject {
                    put("status", if (result.installed) "installed" else "not_installed")
                    put("name", result.name)
                    put("replaced_existing", result.replacedExisting)
                    result.sourceRevision?.let { put("source_revision", it) }
                    result.failure?.let { put("failure", it) }
                    put("activated", false)
                }
            }
            listOf(UIMessagePart.Text(output.toString()))
        },
    ),
)
