package com.poyka.ripdpi.data.uri

import com.poyka.ripdpi.data.wireguard.AmneziaWgParameters
import com.poyka.ripdpi.data.wireguard.AmneziaWgProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmneziaWgUriSignatureExportTest {
    @Test
    fun `each empty signature slot exports as an absent slot`() {
        listOf(
            AmneziaWgParameters(i1 = ""),
            AmneziaWgParameters(i2 = ""),
            AmneziaWgParameters(i3 = ""),
            AmneziaWgParameters(i4 = ""),
            AmneziaWgParameters(i5 = ""),
        ).forEach { awg ->
            awg.requireSupportedProfile()

            assertEquals(Profile, AmneziaWgUriCodec.decode(AmneziaWgUriCodec.encode(Profile.copy(awg = awg))))
        }
    }

    @Test
    fun `mixed empty and nonempty signature slots preserve their meaning`() {
        val profile =
            Profile.copy(awg = AmneziaWgParameters(i1 = "", i2 = "<b 0x01>", i3 = "", i4 = "<r 1>", i5 = ""))
        val expected = Profile.copy(awg = AmneziaWgParameters(i2 = "<b 0x01>", i4 = "<r 1>"))

        assertEquals(expected, AmneziaWgUriCodec.decode(AmneziaWgUriCodec.encode(profile)))
    }

    @Test
    fun `externally supplied empty signature slots remain invalid`() {
        val base = AmneziaWgUriCodec.encode(Profile).substringBefore('#')

        (1..5).forEach { slot ->
            assertNull("i$slot", AmneziaWgUriCodec.decode("$base&i$slot="))
        }
    }

    private companion object {
        val Profile =
            AmneziaWgProfile(
                name = "Test peer",
                host = "192.0.2.9",
                port = 51820,
                privateKey = "test-private",
                publicKey = "test-public",
            )
    }
}
