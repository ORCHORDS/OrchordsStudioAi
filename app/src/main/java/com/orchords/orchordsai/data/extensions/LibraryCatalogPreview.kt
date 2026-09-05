package com.orchords.orchordsai.data.extensions

enum class LibraryContentKind { MODE, LOREBOOK, SKILL }
data class LibraryPreviewItem(
    val id: String,
    val kind: LibraryContentKind,
    val name: String,
    val summary: String,
    val body: String,
)

/** Definition preview only; it makes no claims about installation, selection or connectivity. */
fun libraryPreviewItems(catalog: LibraryCatalog): List<LibraryPreviewItem> = buildList {
    catalog.modes.forEach { add(LibraryPreviewItem("mode:${it.id}", LibraryContentKind.MODE, it.name, it.body, it.body)) }
    catalog.lorebooks.forEach { book ->
        add(LibraryPreviewItem("lorebook:${book.id}", LibraryContentKind.LOREBOOK, book.name, book.description,
            book.entries.joinToString("\n\n") { entry ->
                "${entry.name}\n${entry.keywords.joinToString(", ")}\n\n${entry.body}"
            }))
    }
    catalog.skills.forEach { add(LibraryPreviewItem("skill:${it.name}", LibraryContentKind.SKILL, it.name, it.description, it.body)) }
}

/** Search includes names, descriptions and guidance; category is an independent hard filter. */
fun filterLibraryPreview(
    items: List<LibraryPreviewItem>,
    query: String,
    kind: LibraryContentKind? = null,
): List<LibraryPreviewItem> {
    val terms = query.trim().take(256).split(Regex("\\s+")).filter { it.isNotEmpty() }
    return items.filter { item ->
        (kind == null || item.kind == kind) && terms.all { term ->
            item.name.contains(term, ignoreCase = true) || item.summary.contains(term, ignoreCase = true) ||
                item.body.contains(term, ignoreCase = true)
        }
    }
}
