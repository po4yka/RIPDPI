package com.poyka.ripdpi.data.awg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [AwgProfileForm.toActivationRequest]: the pure projection of the
 * editor form (plus the editor-only transport fields) into the self-contained
 * [AwgActivationRequest] the service layer hands to the AmneziaWG runtime.
 */
class AwgActivationRequestTest {
    private fun form() =
        AwgProfileForm(
            server = "vpn.example.com",
            serverPort = 51820,
            interfacePrivateKey = "privkey==",
            peerPublicKey = "peerpub==",
            presharedKey = "psk==",
            jc = 4,
            jmin = 40,
            jmax = 70,
            s1 = 50,
            s2 = 100,
            s3 = 7,
            s4 = 9,
            h1 = 1_000_000_001L,
            h2 = 1_000_000_002L,
            h3 = 1_000_000_003L,
            h4 = 1_000_000_004L,
            i1 = "deadbeef",
            i5 = "cafe",
            cohortId = "rtk_south",
        )

    @Test
    fun `identity, PSK and transport fields project verbatim`() {
        val request =
            form().toActivationRequest(
                profileId = "awg-1",
                interfaceAddressV4 = "10.8.0.2/32",
                mtu = 1280,
                persistentKeepalive = 25,
            )

        assertEquals("awg-1", request.profileId)
        assertEquals("vpn.example.com", request.endpointHost)
        assertEquals(51820, request.endpointPort)
        assertEquals("privkey==", request.privateKey)
        assertEquals("peerpub==", request.peerPublicKey)
        assertEquals("psk==", request.presharedKey)
        assertEquals("10.8.0.2/32", request.interfaceAddressV4)
        assertEquals(1280, request.mtu)
        assertEquals(25, request.persistentKeepalive)
    }

    @Test
    fun `the full obfuscation group including special junk projects`() {
        val obf =
            form().toActivationRequest(profileId = "awg-1", interfaceAddressV4 = "10.8.0.2/32").obfuscation

        assertEquals(4, obf.jc)
        assertEquals(40, obf.jmin)
        assertEquals(70, obf.jmax)
        assertEquals(50, obf.s1)
        assertEquals(100, obf.s2)
        assertEquals(7, obf.s3)
        assertEquals(9, obf.s4)
        assertEquals(1_000_000_001L, obf.h1)
        assertEquals(1_000_000_004L, obf.h4)
        assertEquals("deadbeef", obf.i1)
        assertEquals("cafe", obf.i5)
    }

    @Test
    fun `mtu and keepalive fall back to defaults when omitted`() {
        val request = form().toActivationRequest(profileId = "awg-1", interfaceAddressV4 = "10.8.0.2/32")

        assertEquals(AwgActivationRequest.DEFAULT_MTU, request.mtu)
        assertEquals(0, request.persistentKeepalive)
    }
}
