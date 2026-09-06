package com.orchords.orchordsai.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LorebookScanDepthInputPolicyTest {
    @Test
    fun `editor parser accepts only documented scan depth boundaries`() {
        mapOf(
            "0" to 0,
            "4" to 4,
            MAX_LOREBOOK_SCAN_DEPTH.toString() to MAX_LOREBOOK_SCAN_DEPTH,
        ).forEach { (input, expected) ->
            assertEquals(expected, parseLorebookScanDepthOrNull(input))
        }
    }

    @Test
    fun `editor parser rejects negative excessive malformed and overflow values`() {
        listOf(
            "-1",
            Int.MIN_VALUE.toString(),
            (MAX_LOREBOOK_SCAN_DEPTH + 1).toString(),
            Int.MAX_VALUE.toString(),
            "2147483648",
            "",
            " ",
            "abc",
            "4.5",
        ).forEach { input ->
            assertNull("input=$input", parseLorebookScanDepthOrNull(input))
        }
    }
}
