package com.orchords.orchordsai.data.files

import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillContentReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `reads complete UTF-8 instructions including the exact byte limit`() {
        val file = temporary.newFile()
        file.writeText("Read 日本語 café and preserve meaning")
        assertEquals(file.readText(), readBoundedSkillText(file))
        file.writeBytes(ByteArray(MAX_SKILL_CONTENT_BYTES) { 'a'.code.toByte() })
        assertEquals(MAX_SKILL_CONTENT_BYTES, readBoundedSkillText(file).length)
    }

    @Test
    fun `oversized ASCII and multibyte content is rejected rather than truncated`() {
        val file = temporary.newFile()
        file.writeBytes(ByteArray(MAX_SKILL_CONTENT_BYTES + 1) { 'a'.code.toByte() })
        assertTrue(runCatching { readBoundedSkillText(file) }.exceptionOrNull() is IllegalArgumentException)
        file.writeText("é".repeat(MAX_SKILL_CONTENT_BYTES / 2 + 1))
        assertTrue(runCatching { readBoundedSkillText(file) }.isFailure)
    }

    @Test
    fun `malformed UTF-8 binary directories and missing files fail safely`() {
        val file = temporary.newFile()
        file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue(runCatching { readBoundedSkillText(file) }.exceptionOrNull() is CharacterCodingException)
        file.writeBytes(byteArrayOf(65, 0, 66))
        assertTrue(runCatching { readBoundedSkillText(file) }.isFailure)
        assertTrue(runCatching { readBoundedSkillText(temporary.root) }.isFailure)
        assertTrue(runCatching { readBoundedSkillText(temporary.root.resolve("absent")) }.isFailure)
    }

    @Test
    fun `metadata delimiters cannot close or add advertised skill elements`() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", escapeSkillMetadata("&<>\"'"))
        assertEquals("Ordinary text", escapeSkillMetadata("Ordinary text"))
    }
}
