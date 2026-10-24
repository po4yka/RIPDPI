package com.poyka.ripdpi.data.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneziaWgWireProfileTest {
    @Test
    fun `range boundaries and canonical grammar are strict`() {
        assertEquals(100L..199L, AmneziaWgWireProfile.headerRange("100-199"))
        assertEquals(4_294_967_295L..4_294_967_295L, AmneziaWgWireProfile.headerRange("4294967295"))
        listOf("", "01", "+1", " 1", "1 ", "1-1", "2-1", "1-02", "4294967296", "1-2-3").forEach {
            assertNull(it, AmneziaWgWireProfile.headerRange(it))
        }
        assertTrue(AmneziaWgWireProfile.headerIsValid("1", 1))
        assertFalse(AmneziaWgWireProfile.headerIsValid("1", 2))
        assertFalse(AmneziaWgWireProfile.headerIsValid("0-100", 1))
    }

    @Test
    fun `all signature tags count exact bytes and enforce bounds`() {
        assertEquals(12, AmneziaWgWireProfile.signatureSize("<b 0x01ab><r 2><rd 2><rc 2><t>"))
        assertEquals(0, AmneziaWgWireProfile.signatureSize(""))
        assertEquals(1280, AmneziaWgWireProfile.signatureSize("<r 1280>"))
        assertEquals(256, AmneziaWgWireProfile.signatureSize("<t>".repeat(64)))
        listOf(
            "deadbeef",
            "<b 0x>",
            "<b 0xA0>",
            "<b 0x0>",
            "<r 0>",
            "<r 01>",
            "<r 1281>",
            "<t >",
            "<d>",
            "<t> ",
            "<t",
            "<r 1><r 1280>",
            "<r  1>",
            "<t>".repeat(65),
            "<b 0x${"aa".repeat(1281)}>",
        ).forEach {
            assertNull(it, AmneziaWgWireProfile.signatureSize(it))
        }
    }

    @Test
    fun `overlap and unknown profile refuse activation while disjoint ranges work`() {
        val valid =
            AmneziaWgParameters(
                h1 = "100-199",
                h2 = "200-299",
                h3 = "300-399",
                h4 = "400-499",
                i1 = "<b 0x01><r 16><rd 8><rc 8><t>",
            )
        valid.requireSupportedProfile()
        listOf(valid.copy(h2 = "199-299"), valid.copy(wireProfile = "future"), valid.copy(i1 = "00")).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.requireSupportedProfile() }
        }
    }

    @Test
    fun `scalar limits reject invalid profiles before activation`() {
        val valid = AmneziaWgParameters(jc = 128, jmin = 1280, jmax = 1280, s1 = 1280, s2 = 1280)
        valid.requireSupportedProfile()
        listOf(
            valid.copy(jc = -1),
            valid.copy(jc = 129),
            valid.copy(jc = Int.MAX_VALUE),
            valid.copy(jmin = -1),
            valid.copy(jmax = 1281),
            valid.copy(s1 = -1),
            valid.copy(s2 = 1281),
            valid.copy(jc = 0, jmin = 1, jmax = 0),
            valid.copy(s1 = 0, s2 = 56),
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.requireSupportedProfile() }
        }
    }

    @Test
    fun `conf preserves ranges and signatures and rejects unsupported options`() {
        val conf =
            """
            [Interface]
            PrivateKey = fixture
            H1 = 100-199
            H2 = 200-299
            H3 = 300-399
            H4 = 400-499
            I1 = <b 0x01><r 4><rd 2><rc 2><t>
            """.trimIndent()
        val parsed = WireGuardConfParser.parse(conf) as AmneziaWgConfig
        assertEquals("100-199", parsed.awg.h1)
        assertEquals("<b 0x01><r 4><rd 2><rc 2><t>", parsed.awg.i1)
        listOf(
            "HeaderProtectionKey",
            "ContentPaddingAddition",
            "RandomTrailers",
            "DisableCookies",
            "RekeyAfterTime",
            "RekeyTimeout",
            "RejectAfterTime",
            "KeepaliveTimeout",
            "MaxHandshakeAttempts",
        ).forEach {
            assertThrows(it, IllegalArgumentException::class.java) { WireGuardConfParser.parse("$conf\n$it = 1") }
            assertThrows(
                it,
                IllegalArgumentException::class.java,
            ) { WireGuardConfParser.parse("$conf\n[Peer]\n$it = 1") }
        }
    }
}
