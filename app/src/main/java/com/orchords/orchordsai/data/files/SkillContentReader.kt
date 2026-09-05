package com.orchords.orchordsai.data.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_SKILL_CONTENT_BYTES = 128 * 1024

/** Read complete bounded UTF-8 instructions, never a silently truncated procedure. */
internal fun readBoundedSkillText(file: File): String {
    require(file.isFile) { "Skill content is missing or is not a regular file" }
    val bytes = file.inputStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            // Read at most one byte beyond the limit, even when the file grows during reading.
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_SKILL_CONTENT_BYTES + 1 - output.size()))
            if (count < 0) break
            require(output.size() + count <= MAX_SKILL_CONTENT_BYTES) {
                "Skill content exceeds 128 KiB; split instructions into smaller referenced files"
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    require(bytes.none { it == 0.toByte() }) { "Skill content must be UTF-8 text" }
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
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
