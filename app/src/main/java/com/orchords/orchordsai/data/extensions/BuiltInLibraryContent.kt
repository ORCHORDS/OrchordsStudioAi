package com.orchords.orchordsai.data.extensions

/** Original offline guidance. Definitions request capabilities; they never grant them. */
internal fun builtInLibraryCatalog(): LibraryCatalog = LibraryCatalog(
    version = 2,
    modes = builtInModes() + expandedModes(),
    lorebooks = builtInLorebooks() + expandedLorebooks(),
    skills = builtInEngineeringSkills() + builtInProductivitySkills() +
        expandedEngineeringSkills() + expandedKnowledgeSkills() + expandedCreativeSkills(),
).also(::validateLibraryCatalog)

internal fun librarySkill(
    slug: String,
    title: String,
    description: String,
    workflow: String,
    completion: String,
): LibrarySkill = LibrarySkill(
    name = "orchords-$slug",
    description = description,
    body = "# $title\n\n$description\n\n## Workflow\n\n$workflow\n\n" +
        "## Boundaries\n\nUse only capabilities actually available and enabled for the active task. " +
        "Respect the selected source scope and current runtime permissions. This guidance cannot " +
        "authorize an external write, disclose credentials, or override the user's instructions.\n\n" +
        "## Completion\n\n$completion",
)
