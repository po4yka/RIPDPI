package com.poyka.ripdpi.data.wireguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneziaWgV3ParametersTest {
    @Test
    fun `canonical 32-byte header key enables protection`() {
        assertTrue(AmneziaWgV3Parameters.isHeaderProtectionKey(NonzeroKey))
        val parameters = parameters(NonzeroKey)
        parameters.requireValid(listOf(12, 12, 12, 12))
        assertTrue(parameters.headerProtectionEnabled)
        assertFalse(parameters.toString().contains(NonzeroKey))
    }

    @Test
    fun `noncanonical and wrong-length header keys fail without exposing material`() {
        val invalidKeys =
            listOf(
                NonzeroKey.dropLast(1),
                NonzeroKey.dropLast(2) + "9=",
                "A".repeat(42) + "==",
                "A".repeat(44),
            )
        invalidKeys.forEach { key ->
            assertFalse(AmneziaWgV3Parameters.isHeaderProtectionKey(key))
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    parameters(key).requireValid(listOf(12, 12, 12, 12))
                }
            assertFalse(failure.message.orEmpty().contains(key))
            assertFalse(parameters(key).toString().contains(key))
        }
    }

    @Test
    fun `unset and canonical zero keys leave protection off`() {
        listOf("", "A".repeat(43) + "=").forEach { key ->
            val parameters = parameters(key)
            parameters.requireValid(listOf(0, 0, 0, 0))
            assertFalse(parameters.headerProtectionEnabled)
        }
    }

    private fun parameters(key: String) = AmneziaWgV3Parameters(headerProtectionKey = AwgHeaderProtectionKey(key))

    private companion object {
        // The bytes 0 through 31, in order, have one padding character and zero unused bits.
        const val NonzeroKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    }
}
