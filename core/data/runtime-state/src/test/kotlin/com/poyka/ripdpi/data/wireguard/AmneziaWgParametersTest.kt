package com.poyka.ripdpi.data.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class AmneziaWgParametersTest {
    @Test
    fun `S3-S4 accept zero and padding sizes up to one packet`() {
        AmneziaWgParameters().requireSupportedProfile()
        AmneziaWgParameters(s3 = 1, s4 = 1280).requireSupportedProfile()
        assertThrows(IllegalArgumentException::class.java) { AmneziaWgParameters(s3 = 1281).requireSupportedProfile() }
        assertThrows(IllegalArgumentException::class.java) { AmneziaWgParameters(s4 = -1).requireSupportedProfile() }
    }

    @Test
    fun `awg 3x header protection with S1-S4 = 12 is a supported profile`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val awg3 =
            AmneziaWgParameters(
                s1 = 12,
                s2 = 12,
                s3 = 12,
                s4 = 12,
                v3 = AmneziaWgV3Parameters(headerProtectionKey = AwgHeaderProtectionKey(key), randomTrailers = true),
            )
        awg3.requireSupportedProfile()
        val short = assertThrows(IllegalArgumentException::class.java) { awg3.copy(s4 = 11).requireSupportedProfile() }
        assertFalse(short.message.orEmpty().contains(key))
        assertFalse("toString must redact the header protection key", awg3.toString().contains(key))
    }

    @Test
    fun `awg 3x validation names the key and never echoes key material`() {
        val short = AmneziaWgParameters(s1 = 11, s2 = 12, s3 = 12, s4 = 12, v3 = protectedV3())
        val prefix = assertThrows(IllegalArgumentException::class.java) { short.requireSupportedProfile() }
        assertEquals("AmneziaWG header protection requires S1 >= 12", prefix.message)
        val badKey = AmneziaWgParameters(v3 = v3Key("c2VjcmV0"))
        val keyError = assertThrows(IllegalArgumentException::class.java) { badKey.requireSupportedProfile() }
        assertFalse(keyError.message.orEmpty().contains("c2VjcmV0"))
        val zeroKey = Base64.getEncoder().encodeToString(ByteArray(32))
        AmneziaWgParameters(v3 = v3Key(zeroKey)).requireSupportedProfile()
        listOf("7-3", "65536", "x").forEach { range ->
            assertThrows(range, IllegalArgumentException::class.java) {
                AmneziaWgParameters(v3 = AmneziaWgV3Parameters(rekeyTimeout = range)).requireSupportedProfile()
            }
        }
        AmneziaWgParameters(v3 = AmneziaWgV3Parameters(rekeyTimeout = "3-3", maxHandshakeAttempts = "15-20"))
            .requireSupportedProfile()
    }

    private fun v3Key(key: String) = AmneziaWgV3Parameters(headerProtectionKey = AwgHeaderProtectionKey(key))

    private fun protectedV3() = v3Key(Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
}
