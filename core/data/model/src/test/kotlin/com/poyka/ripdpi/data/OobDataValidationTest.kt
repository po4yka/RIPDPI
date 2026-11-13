package com.poyka.ripdpi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OobDataValidationTest {
    @Test
    fun `empty data and all byte values are valid`() {
        assertTrue(isValidOobData(""))
        for (code in 0..255) {
            assertTrue(code.toString(), isValidOobData(code.toChar().toString()))
        }
    }

    @Test
    fun `characters outside the byte range and multiple characters are invalid`() {
        for (value in listOf("\u0100", "Ж", "\ud800", "ab", "\u0000\u0000")) {
            assertFalse(isValidOobData(value))
        }
    }
}
