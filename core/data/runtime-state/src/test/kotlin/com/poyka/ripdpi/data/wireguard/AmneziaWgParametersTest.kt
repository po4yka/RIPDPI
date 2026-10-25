package com.poyka.ripdpi.data.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AmneziaWgParametersTest {
    @Test
    fun `arm64 safety treats absent and explicit zero S3-S4 as safe`() {
        AmneziaWgParameters().requireSupportedProfile()
        AmneziaWgParameters(s3 = 0, s4 = 0).requireSupportedProfile()
    }

    @Test
    fun `arm64 safety rejects either non-zero junk-size parameter`() {
        val s3Error =
            assertThrows(IllegalArgumentException::class.java) {
                AmneziaWgParameters(s3 = 1, s4 = 0).requireSupportedProfile()
            }
        val s4Error =
            assertThrows(IllegalArgumentException::class.java) {
                AmneziaWgParameters(s3 = 0, s4 = 1).requireSupportedProfile()
            }
        val bothError =
            assertThrows(IllegalArgumentException::class.java) {
                AmneziaWgParameters(s3 = 1, s4 = 1).requireSupportedProfile()
            }

        assertTrue(s3Error.message.orEmpty().contains("amneziawg-go#110"))
        assertTrue(s4Error.message.orEmpty().contains("amneziawg-go#110"))
        assertTrue(bothError.message.orEmpty().contains("amneziawg-go#110"))
        assertTrue(listOf(s3Error, s4Error, bothError).all { it is AmneziaWgArm64S34FloorException })
    }

    @Test
    fun `awg 3x header protection is well formed but stays blocked by the S3-S4 floor`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val awg3 =
            AmneziaWgParameters(
                s1 = 12,
                s2 = 12,
                s3 = 12,
                s4 = 12,
                v3 = AmneziaWgV3Parameters(headerProtectionKey = AwgHeaderProtectionKey(key), randomTrailers = true),
            )
        awg3.requireWellFormed()
        val floor = assertThrows(AmneziaWgArm64S34FloorException::class.java) { awg3.requireSupportedProfile() }
        assertEquals(12 to 12, floor.s3 to floor.s4)
        assertTrue(floor.message.orEmpty().contains("S3/S4"))
        assertTrue(floor.message.orEmpty().contains("header protection needs S3/S4 >= 12"))
        assertFalse(floor.message.orEmpty().contains(key))
        assertFalse("toString must redact the header protection key", awg3.toString().contains(key))
    }

    @Test
    fun `awg 3x validation names the key and never echoes key material`() {
        val short = AmneziaWgParameters(s1 = 11, s2 = 12, s3 = 12, s4 = 12, v3 = protectedV3())
        val prefix = assertThrows(IllegalArgumentException::class.java) { short.requireWellFormed() }
        assertEquals("AmneziaWG header protection requires S1 >= 12", prefix.message)
        val badKey = AmneziaWgParameters(v3 = v3Key("c2VjcmV0"))
        val keyError = assertThrows(IllegalArgumentException::class.java) { badKey.requireWellFormed() }
        assertFalse(keyError.message.orEmpty().contains("c2VjcmV0"))
        val zeroKey = Base64.getEncoder().encodeToString(ByteArray(32))
        AmneziaWgParameters(v3 = v3Key(zeroKey)).requireSupportedProfile()
        listOf("7-3", "65536", "x").forEach { range ->
            assertThrows(range, IllegalArgumentException::class.java) {
                AmneziaWgParameters(v3 = AmneziaWgV3Parameters(rekeyTimeout = range)).requireWellFormed()
            }
        }
        AmneziaWgParameters(v3 = AmneziaWgV3Parameters(rekeyTimeout = "3-3", maxHandshakeAttempts = "15-20"))
            .requireSupportedProfile()
    }

    private fun v3Key(key: String) = AmneziaWgV3Parameters(headerProtectionKey = AwgHeaderProtectionKey(key))

    private fun protectedV3() = v3Key(Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
}
