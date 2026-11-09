package com.poyka.ripdpi.integration

import com.poyka.ripdpi.data.isValidTuicUuid
import com.poyka.ripdpi.data.parseTuicAlpn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuicCredentialsInstrumentedTest {
    @Test
    fun uuidAndAlpnValidationUsesAndroidRegex() {
        val uuid = "9f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f"

        assertEquals(listOf("tuic", "h3"), parseTuicAlpn(" tuic , h3,, "))
        assertNull(parseTuicAlpn(" , "))
        for (value in listOf(uuid, uuid.uppercase(), uuid.replace("-", ""), "{$uuid}", "urn:uuid:$uuid", " $uuid ")) {
            assertTrue(value, isValidTuicUuid(value))
        }
        for (value in listOf("", "{$uuid", "$uuid}", "{$uuid}}", "{$uuid}x", "not-a-uuid")) {
            assertFalse(value, isValidTuicUuid(value))
        }
        assertFalse(isValidTuicUuid(null))
    }
}
