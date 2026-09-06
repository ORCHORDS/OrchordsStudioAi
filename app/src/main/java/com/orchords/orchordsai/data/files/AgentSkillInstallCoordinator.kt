package com.orchords.orchordsai.data.files

import java.util.LinkedHashMap
import java.util.UUID

private const val AGENT_SKILL_PROPOSAL_TTL_MS = 15L * 60L * 1000L
private const val MAX_PENDING_AGENT_SKILL_PROPOSALS = 16

internal data class AgentSkillInstallProposalSummary(
    val proposalToken: String,
    val source: String,
    val name: String,
    val description: String,
    val compatibility: String?,
    val disableModelInvocation: Boolean,
    val sourceRevision: String?,
    val replacesExisting: Boolean,
    val fileCount: Int,
    val totalBytes: Long,
    val fileNames: List<String>,
    val omittedFileCount: Int,
)

/**
 * Keeps validated proposals local and one-shot. Proposal tokens are opaque routing handles,
 * not authorization: the install tool still requires the normal user-approval boundary.
 */
internal class AgentSkillInstallCoordinator(
    private val installer: SkillInstallService,
    private val acquire: (String, () -> Unit) -> PreparedSkillPackage = { source, checkpoint ->
        GitHubSkillImporter().acquire(source, checkpoint)
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Pending(
        val proposal: SkillInstallProposal,
        val expiresAtMillis: Long,
    )

    private val pending = LinkedHashMap<String, Pending>()

    fun propose(
        source: String,
        checkpoint: () -> Unit = {},
    ): AgentSkillInstallProposalSummary {
        checkpoint()
        // The importer accepts only credential-free HTTPS github.com repository/tree links.
        GitHubSkillSource.parse(source)
        val prepared = acquire(source, checkpoint)
        checkpoint()
        val proposal = installer.propose(source, prepared)
        val token = tokenFactory().take(96)
        require(token.isNotBlank() && token.none { it.isISOControl() }) { "Invalid proposal token" }

        synchronized(pending) {
            pruneExpiredLocked()
            require(pending.size < MAX_PENDING_AGENT_SKILL_PROPOSALS) {
                "Too many pending skill install proposals"
            }
            require(token !in pending) { "Duplicate proposal token" }
            pending[token] = Pending(
                proposal = proposal,
                expiresAtMillis = nowMillis() + AGENT_SKILL_PROPOSAL_TTL_MS,
            )
        }

        return AgentSkillInstallProposalSummary(
            proposalToken = token,
            source = proposal.source,
            name = proposal.name,
            description = proposal.description,
            compatibility = proposal.compatibility,
            disableModelInvocation = proposal.disableModelInvocation,
            sourceRevision = proposal.sourceRevision,
            replacesExisting = proposal.replacesExisting,
            fileCount = proposal.fileCount,
            totalBytes = proposal.totalBytes,
            fileNames = proposal.fileNames,
            omittedFileCount = proposal.omittedFileCount,
        )
    }

    fun install(proposalToken: String): SkillInstallResult? {
        val pendingProposal = synchronized(pending) {
            pruneExpiredLocked()
            pending.remove(proposalToken)
        } ?: return null
        return installer.install(pendingProposal.proposal)
    }

    fun discard(proposalToken: String): Boolean = synchronized(pending) {
        pruneExpiredLocked()
        pending.remove(proposalToken) != null
    }

    internal fun pendingCount(): Int = synchronized(pending) {
        pruneExpiredLocked()
        pending.size
    }

    private fun pruneExpiredLocked() {
        val now = nowMillis()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtMillis <= now) iterator.remove()
        }
    }
}
