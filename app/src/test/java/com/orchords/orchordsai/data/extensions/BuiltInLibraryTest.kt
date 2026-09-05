package com.orchords.orchordsai.data.extensions

import com.orchords.ai.core.MessageRole
import com.orchords.orchordsai.data.files.SkillFrontmatterParser
import com.orchords.orchordsai.data.model.InjectionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BuiltInLibraryTest {
    @Test
    fun catalogHasCompleteDistinctContent() {
        val catalog = BuiltInLibrary.catalog
        assertEquals(2, catalog.version)
        validateLibraryCatalog(catalog)
        assertEquals(24, catalog.modes.size)
        assertEquals(16, catalog.lorebooks.size)
        assertEquals(56, catalog.lorebooks.sumOf { it.entries.size })
        assertEquals(60, catalog.skills.size)
        val ids = catalog.modes.map { it.id } + catalog.lorebooks.map { it.id } +
            catalog.lorebooks.flatMap { it.entries }.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        ids.forEach { assertEquals(it, UUID.fromString(it).toString()) }
        assertEquals(60, catalog.skills.map { it.name }.distinct().size)
        catalog.skills.forEach { assertTrue(it.body.length in 600..16000) }
    }

    @Test
    fun generatedSkillsRoundTripThroughTheRealFrontmatterParser() {
        BuiltInLibrary.catalog.skills.forEach { skill ->
            val file = skill.skillFile()
            val parsed = SkillFrontmatterParser.parse(file)
            assertEquals(skill.name, parsed["name"])
            assertEquals(skill.description, parsed["description"])
            assertEquals(skill.body, SkillFrontmatterParser.extractBody(file).trimEnd())
        }
    }

    @Test
    fun nativeDefinitionsUseUserGuidanceAndBoundedPlainTriggers() {
        BuiltInLibrary.catalog.modes.forEach { definition ->
            val mode = definition.toModeInjection()
            assertEquals(definition.id, mode.id.toString())
            assertEquals(MessageRole.USER, mode.role)
            assertEquals(InjectionPosition.BOTTOM_OF_CHAT, mode.position)
        }
        BuiltInLibrary.catalog.lorebooks.forEach { definition ->
            val book = definition.toLorebook()
            assertEquals(definition.id, book.id.toString())
            book.entries.forEach { entry ->
                assertEquals(4, entry.scanDepth)
                assertFalse(entry.useRegex)
                assertFalse(entry.constantActive)
                assertEquals(MessageRole.USER, entry.role)
                assertEquals(InjectionPosition.BOTTOM_OF_CHAT, entry.position)
            }
        }
    }

    @Test
    fun appendPreservesUserEditsAndReinstallIsIdempotent() {
        val builtIns = BuiltInLibrary.catalog.modes
        val edited = builtIns.first().copy(body = "Keep my custom text")
        val installed = appendMissingById(listOf(edited), builtIns) { it.id }
        assertEquals(edited, installed.first())
        assertEquals(24, installed.size)
        assertEquals(installed, appendMissingById(installed, builtIns) { it.id })
        assertEquals(1, appendMissingById(emptyList(), listOf(edited, edited)) { it.id }.size)
    }
}
