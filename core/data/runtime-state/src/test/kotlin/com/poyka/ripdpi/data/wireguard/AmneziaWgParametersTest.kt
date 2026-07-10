package com.poyka.ripdpi.data.wireguard

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneziaWgParametersTest {
    @Test
    fun `arm64 safety treats absent and explicit zero S3-S4 as safe`() {
        AmneziaWgParameters().requireArm64Safe()
        AmneziaWgParameters(s3 = 0, s4 = 0).requireArm64Safe()
    }

    @Test
    fun `arm64 safety rejects either non-zero junk-size parameter`() {
        val s3Error =
            assertThrows(IllegalArgumentException::class.java) {
                AmneziaWgParameters(s3 = 1, s4 = 0).requireArm64Safe()
            }
        val s4Error =
            assertThrows(IllegalArgumentException::class.java) {
                AmneziaWgParameters(s3 = 0, s4 = 1).requireArm64Safe()
            }
        val bothError =
            assertThrows(IllegalArgumentException::class.java) {
                AmneziaWgParameters(s3 = 1, s4 = 1).requireArm64Safe()
            }

        assertTrue(s3Error.message.orEmpty().contains("amneziawg-go#110"))
        assertTrue(s4Error.message.orEmpty().contains("amneziawg-go#110"))
        assertTrue(bothError.message.orEmpty().contains("amneziawg-go#110"))
    }
}
