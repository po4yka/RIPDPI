package com.poyka.ripdpi.data.awg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Tests for [AwgProfileForm.toActivationRequest]: the pure projection of the
 * editor form (plus the editor-only transport fields) into the self-contained
 * [AwgActivationRequest] the service layer hands to the AmneziaWG runtime.
 */
class AwgActivationRequestTest {
    @Test
    fun `runtime readiness accepts numeric dual stack policy without resolving an endpoint`() {
        runtimeRequest()
            .copy(
                interfaceAddressV6 = "FD00:0000:0000:0000:0000:0000:0000:0002/128",
                dnsServers = listOf("10.8.0.1", "FD00:0:0:0:0:0:0:1"),
                allowedIps = listOf("10.8.0.0/24", "FD00::/64"),
            ).requireRuntimeReady()
    }

    @Test
    fun `runtime readiness rejects malformed or mismatched interface policy`() {
        val request = runtimeRequest()
        val invalidRequests =
            listOf(
                request.copy(interfaceAddressV6 = "fd00:::2/128"),
                request.copy(interfaceAddressV6 = "fd00::gg/128"),
                request.copy(interfaceAddressV6 = "1:2:3:4:5:6:7/128"),
                request.copy(interfaceAddressV6 = "[fd00::2]/128"),
                request.copy(interfaceAddressV6 = "fe80::2%wlan0/64"),
                request.copy(interfaceAddressV6 = "::ffff:192.0.2.1/128"),
                request.copy(interfaceAddressV6 = "fd00::2/129"),
                request.copy(interfaceAddressV6 = "fd00::2/+64"),
                request.copy(interfaceAddressV6 = "fd00::2/064"),
                request.copy(interfaceAddressV4 = "10.8.0.2/033"),
                request.copy(dnsServers = listOf("dns.example.com")),
                request.copy(dnsServers = listOf("1.1.1.1/32")),
                request.copy(dnsServers = listOf("1.1.1.999")),
                request.copy(dnsServers = listOf("01.1.1.1")),
                request.copy(dnsServers = listOf("")),
                request.copy(dnsServers = listOf("fd00::1")),
                request.copy(allowedIps = emptyList()),
                request.copy(allowedIps = listOf("example.com/24")),
                request.copy(allowedIps = listOf("10.0.0.0/33")),
                request.copy(allowedIps = listOf("0.0.0.0/+0")),
                request.copy(allowedIps = listOf("::/0")),
                request.copy(interfaceAddressV6 = "fd00::2/128", allowedIps = listOf("fd00:::0/64")),
                request.copy(interfaceAddressV6 = "fd00::2/128", allowedIps = listOf("::/129")),
            )

        invalidRequests.forEachIndexed { index, invalid ->
            assertThrows("invalid interface policy case $index", IllegalArgumentException::class.java) {
                invalid.requireRuntimeReady()
            }
        }
    }

    private fun runtimeRequest(): AwgActivationRequest =
        AwgActivationRequest(
            profileId = "awg-runtime-ready",
            privateKey = Base64.getEncoder().encodeToString(ByteArray(32) { 1 }),
            peerPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            endpointHost = "vpn.example.com",
            endpointPort = 51820,
            interfaceAddressV4 = "10.8.0.2/32",
        )

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

    @Test
    fun `carrier defaults to UDP with a blank URL`() {
        val request = form().toActivationRequest(profileId = "awg-1", interfaceAddressV4 = "10.8.0.2/32")

        assertEquals(AwgActivationRequest.CARRIER_UDP, request.carrier)
        assertEquals("", request.carrierWsUrl)
    }

    @Test
    fun `a WS carrier selection and its URL project verbatim`() {
        val wsForm =
            form().copy(
                carrier = AwgActivationRequest.CARRIER_WS,
                carrierWsUrl = "wss://vpn.example.com:443/path",
            )

        val request = wsForm.toActivationRequest(profileId = "awg-1", interfaceAddressV4 = "10.8.0.2/32")

        assertEquals(AwgActivationRequest.CARRIER_WS, request.carrier)
        assertEquals("wss://vpn.example.com:443/path", request.carrierWsUrl)
    }

    @Test
    fun `arm64 safety accepts zero s3 and s4`() {
        AwgActivationObfuscation(s3 = 0, s4 = 0).requireArm64Safe()
    }

    @Test
    fun `arm64 safety rejects non-zero s3 or s4 with the upstream issue`() {
        val s3Error =
            assertThrows(IllegalArgumentException::class.java) {
                AwgActivationObfuscation(s3 = 1).requireArm64Safe()
            }
        val s4Error =
            assertThrows(IllegalArgumentException::class.java) {
                AwgActivationObfuscation(s4 = 1).requireArm64Safe()
            }
        val bothError =
            assertThrows(IllegalArgumentException::class.java) {
                AwgActivationObfuscation(s3 = 1, s4 = 1).requireArm64Safe()
            }

        assertTrue(s3Error.message.orEmpty().contains("amneziawg-go#110"))
        assertTrue(s4Error.message.orEmpty().contains("amneziawg-go#110"))
        assertTrue(bothError.message.orEmpty().contains("amneziawg-go#110"))
    }
}
