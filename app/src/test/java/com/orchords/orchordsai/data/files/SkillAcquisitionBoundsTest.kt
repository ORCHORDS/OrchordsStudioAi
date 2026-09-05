package com.orchords.orchordsai.data.files

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class SkillAcquisitionBoundsTest {
    @Test fun `reader accepts exact boundary and rejects one extra byte`() {
        assertArrayEquals(byteArrayOf(1, 2), readBoundedSkillBytes(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedSkillBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2)
        }
        assertTrue(readBoundedSkillBytes(ByteArrayInputStream(byteArrayOf()), 0).isEmpty())
    }

    @Test fun `reader never consumes an unbounded stream beyond limit plus one`() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int { consumed++; return 65 }
        }
        assertThrows(IllegalArgumentException::class.java) { readBoundedSkillBytes(stream, 10) }
        assertEquals(11, consumed)
    }

    @Test fun `zero progress read cannot spin forever`() {
        val stream = object : ByteArrayInputStream(byteArrayOf(65)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertArrayEquals(byteArrayOf(65), readBoundedSkillBytes(stream, 1))
    }

    @Test fun `strict decoder rejects malformed UTF8 and NUL`() {
        assertThrows(CharacterCodingException::class.java) {
            decodeSkillText(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) { decodeSkillText(byteArrayOf(0)) }
        assertEquals("text", decodeSkillText("text".toByteArray()))
    }
}
