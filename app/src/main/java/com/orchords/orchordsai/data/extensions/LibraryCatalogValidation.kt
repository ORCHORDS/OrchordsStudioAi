package com.orchords.orchordsai.data.extensions

import java.util.UUID

/** Build-time/runtime integrity checks for first-party definitions, not imported user records. */
internal fun validateLibraryCatalog(catalog: LibraryCatalog) {
    require(catalog.version > 0)
    fun title(value: String) = require(value.isNotBlank() && value.length <= 120 && value.none(Char::isISOControl))
    fun body(value: String, limit: Int) = require(value.isNotBlank() && value.length <= limit && '\u0000' !in value)
    val ids = HashSet<String>()
    fun id(value: String) {
        require(UUID.fromString(value).toString() == value && ids.add(value)) { "Invalid or duplicate library identity" }
    }
    catalog.modes.forEach { id(it.id); title(it.name); body(it.body, 8192) }
    catalog.lorebooks.forEach { book ->
        id(book.id); title(book.name); body(book.description, 1024)
        require(book.entries.isNotEmpty())
        book.entries.forEach { entry ->
            id(entry.id); title(entry.name); body(entry.body, 8192)
            require(entry.keywords.size in 1..16)
            require(entry.keywords.all { it.isNotBlank() && it.length <= 120 && it.none(Char::isISOControl) })
            require(entry.keywords.distinct().size == entry.keywords.size)
        }
    }
    require(catalog.modes.map { it.name }.distinct().size == catalog.modes.size)
    require(catalog.lorebooks.map { it.name }.distinct().size == catalog.lorebooks.size)
    require(catalog.skills.map { it.name }.distinct().size == catalog.skills.size)
    catalog.skills.forEach { skill ->
        body(skill.body, 16000)
        require(skill.name.startsWith("orchords-"))
        skill.skillFile() // Canonical name/description validation and escaping.
    }
    val bodies = catalog.modes.map { it.body } + catalog.lorebooks.flatMap { it.entries }.map { it.body } + catalog.skills.map { it.body }
    require(bodies.distinct().size == bodies.size) { "Duplicate library body" }
}
