package com.orchords.orchordsai.data.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal const val MAX_SKILL_CONTENT_BYTES = 128 * 1024

/** Reads at most one byte beyond the limit; the caller owns the stream. */
internal fun readBoundedSkillBytes(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes in 0 until Int.MAX_VALUE)
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1))
        if (count < 0) break
        if (count == 0) {
            val next = input.read()
            if (next < 0) break
            require(output.size() < maxBytes) { "Skill content exceeds the byte limit" }
            output.write(next)
        } else {
            require(count <= maxBytes - output.size()) { "Skill content exceeds the byte limit" }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

/** Decode complete bounded instructions, never replace malformed bytes or truncate a procedure. */
internal fun decodeSkillText(bytes: ByteArray): String {
    require(bytes.size <= MAX_SKILL_CONTENT_BYTES) { "Skill content exceeds 128 KiB" }
    require(bytes.none { it == 0.toByte() }) { "Skill content must be UTF-8 text" }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("Skill content must be valid UTF-8 text", error)
    }
}

internal fun readBoundedSkillText(file: File): String {
    require(file.isFile) { "Skill content is missing or is not a regular file" }
    return decodeSkillText(file.inputStream().use { readBoundedSkillBytes(it, MAX_SKILL_CONTENT_BYTES) })
}

/** Delimiter escaping is structural protection, not a grant of trust to skill descriptions. */
internal fun escapeSkillMetadata(value: String): String = buildString {
    value.forEach { character ->
        append(when (character) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&apos;"
            else -> character.toString()
        })
    }
}
