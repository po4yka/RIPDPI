package com.poyka.ripdpi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuicCredentialsTest {
    private val hyphenated = "9f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f"

    @Test
    fun `accepts every uuid form the native parser accepts`() {
        listOf(
            hyphenated,
            hyphenated.uppercase(),
            hyphenated.replace("-", ""),
            "{$hyphenated}",
            "urn:uuid:$hyphenated",
            "  $hyphenated\n",
        ).forEach { assertTrue(it, isValidTuicUuid(it)) }
    }

    @Test
    fun `rejects malformed uuids`() {
        listOf(
            null,
            "",
            "tuic-uuid",
            hyphenated.dropLast(1),
            hyphenated + "0",
            "{$hyphenated",
            "9f1c2d3e4a5b-4c6d-8e7f-0a1b2c3d4e5f",
            "zf1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f",
        ).forEach { assertFalse(it.toString(), isValidTuicUuid(it)) }
    }
}
