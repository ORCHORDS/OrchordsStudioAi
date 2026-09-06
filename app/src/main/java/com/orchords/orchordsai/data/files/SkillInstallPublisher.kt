package com.orchords.orchordsai.data.files

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One publication adapter shared by manual import and agent proposals. */
internal fun SkillManager.createSkillInstallService(
    nowMillis: () -> Long = System::currentTimeMillis,
): SkillInstallService = SkillInstallService(
    existingSkillNames = { listSkills().mapTo(linkedSetOf()) { it.name } },
    installPackage = { prepared, provenance ->
        if (prepared.preserveAssets) {
            saveSkill(
                prepared.name,
                decodeSkillText(requireNotNull(prepared.files["SKILL.md"])),
            ) != null
        } else {
            val provenanceBytes = buildJsonObject {
                put("schema", 1)
                put("source", provenance.source)
                provenance.sourceRevision?.let { put("source_revision", it) }
                put("installed_at_epoch_ms", nowMillis())
            }.toString().toByteArray(Charsets.UTF_8)
            val publishedFiles = LinkedHashMap(prepared.files)
            publishedFiles[SKILL_INSTALL_PROVENANCE_PATH] = provenanceBytes
            saveSkillFileBytesAtomically(prepared.name, publishedFiles)
        }
    },
)
