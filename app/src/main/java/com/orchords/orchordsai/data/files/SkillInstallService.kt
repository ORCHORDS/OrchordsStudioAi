package com.orchords.orchordsai.data.files

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal const val SKILL_INSTALL_PROVENANCE_PATH = ".orchords/provenance.json"
private const val MAX_PROPOSAL_FILE_PREVIEW = 32
private const val MAX_PROPOSAL_FILE_NAME_CHARS = 160

internal data class SkillInstallProvenance(
    val source: String,
    val sourceRevision: String?,
)

/**
 * Team 2 proposal/publication boundary. Preparing a proposal never installs files.
 * Callers must obtain any required user approval before calling [install]; this
 * service is not an approval engine or a grant to execute downloaded content.
 */
internal class SkillInstallService(
    private val existingSkillNames: () -> Set<String>,
    private val installPackage: (PreparedSkillPackage, SkillInstallProvenance) -> Boolean,
) {
    constructor(
        existingSkillNames: () -> Set<String>,
        installPackage: (PreparedSkillPackage) -> Boolean,
    ) : this(existingSkillNames, { prepared, _ -> installPackage(prepared) })

    private val owner = Any()

    fun propose(source: String, prepared: PreparedSkillPackage): SkillInstallProposal {
        require(prepared.files.keys.none { it.equals(SKILL_INSTALL_PROVENANCE_PATH, ignoreCase = true) }) {
            "Skill package contains a reserved ORCHORDS metadata path"
        }
        val metadata = SkillPackageValidator.validate(prepared.name, prepared.files)
        require(prepared.sourceRevision == null ||
            (prepared.sourceRevision.length <= 128 && prepared.sourceRevision.none { it.isISOControl() })) {
            "Invalid skill source revision"
        }
        val snapshot = prepared.copy(files = prepared.files.mapValues { (_, bytes) -> bytes.copyOf() })
        SkillPackageValidator.validate(snapshot.name, snapshot.files)
        val boundedSource = source.take(240).map { if (it.isISOControl()) ' ' else it }.joinToString("")
        return SkillInstallProposal(
            source = boundedSource,
            prepared = snapshot,
            replacesExisting = snapshot.name in existingSkillNames(),
            metadata = metadata,
            provenance = SkillInstallProvenance(
                source = boundedSource,
                sourceRevision = snapshot.sourceRevision,
            ),
            owner = owner,
        )
    }

    fun install(proposal: SkillInstallProposal): SkillInstallResult {
        val prepared = proposal.consume(owner) ?: return proposal.result(false, "invalid_or_consumed_proposal")
        return try {
            // A newly appeared/disappeared destination requires a fresh proposal.
            if ((prepared.name in existingSkillNames()) != proposal.replacesExisting) {
                proposal.result(false, "destination_changed")
            } else {
                SkillPackageValidator.validate(prepared.name, prepared.files)
                val installed = installPackage(prepared, proposal.provenance)
                proposal.result(installed, if (installed) null else "publication_failed")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not expose file contents, provider credentials or arbitrary exception text.
            proposal.result(false, "publication_failed")
        }
    }
}

/** Read-only metadata backed by a private, defensive package snapshot. */
internal class SkillInstallProposal internal constructor(
    val source: String,
    private val prepared: PreparedSkillPackage,
    val replacesExisting: Boolean,
    metadata: SkillPackageMetadata,
    internal val provenance: SkillInstallProvenance,
    private val owner: Any,
) {
    val name: String = prepared.name
    val description: String = metadata.description.take(500)
    val compatibility: String? = metadata.compatibility?.take(300)
    val disableModelInvocation: Boolean = metadata.disableModelInvocation
    val sourceRevision: String? = prepared.sourceRevision
    val fileCount: Int = prepared.files.size
    val totalBytes: Long = prepared.files.values.sumOf { it.size.toLong() }
    val fileNames: List<String> = prepared.files.keys.sorted()
        .take(MAX_PROPOSAL_FILE_PREVIEW)
        .map { it.take(MAX_PROPOSAL_FILE_NAME_CHARS) }
    val omittedFileCount: Int = (fileCount - fileNames.size).coerceAtLeast(0)
    private val consumed = AtomicBoolean(false)

    internal fun consume(expectedOwner: Any): PreparedSkillPackage? {
        if (owner !== expectedOwner || !consumed.compareAndSet(false, true)) return null
        return prepared
    }

    internal fun result(installed: Boolean, failure: String?): SkillInstallResult = SkillInstallResult(
        installed = installed,
        replacedExisting = replacesExisting,
        name = name,
        sourceRevision = sourceRevision,
        failure = failure,
    )
}

/** replacedExisting describes the proposed destination even when publication fails. */
internal data class SkillInstallResult(
    val installed: Boolean,
    val replacedExisting: Boolean,
    val name: String,
    val sourceRevision: String?,
    val failure: String? = null,
)
