package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.requireRuntimeReady
import com.poyka.ripdpi.data.subscription.SingBoxParseResult
import com.poyka.ripdpi.data.subscription.SingBoxSubscriptionParser
import com.poyka.ripdpi.data.subscription.WireGuardIniSubscriptionParser
import com.poyka.ripdpi.data.subscription.toActivationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardActivationMappersTest {
    @Test
    fun `sing-box AWG retains dual-stack DNS and scoped routes`() {
        val profile =
            singBox("\"dns\":[\"9.9.9.9\",\"2001:4860:4860::8888\"],", "\"allowed_ips\":[\"10.0.0.0/8\",\"fd00::/8\"],")
        val request = profile.toActivationRequest()
        assertEquals(listOf("9.9.9.9", "2001:4860:4860::8888"), request.dnsServers)
        assertEquals(listOf("10.0.0.0/8", "fd00::/8"), request.allowedIps)
        request.requireRuntimeReady()
    }

    @Test
    fun `INI WireGuard retains dual-stack DNS and scoped routes`() {
        val profile = WireGuardIniSubscriptionParser.parse(ini(), "group").profiles.single()
        val request = profile.toActivationRequest()
        assertEquals(listOf("9.9.9.9", "2001:4860:4860::8888"), request.dnsServers)
        assertEquals(listOf("10.0.0.0/8", "fd00::/8"), request.allowedIps)
        request.requireRuntimeReady()
    }

    @Test
    fun `INI AWG retains dual-stack DNS and scoped routes`() {
        val profile = WireGuardIniSubscriptionParser.parse(ini("Jc = 4"), "group").amneziaWgProfiles.single()
        val request = profile.toActivationRequest()
        assertEquals(listOf("9.9.9.9", "2001:4860:4860::8888"), request.dnsServers)
        assertEquals(listOf("10.0.0.0/8", "fd00::/8"), request.allowedIps)
        request.requireRuntimeReady()
    }

    @Test
    fun `interface address families do not depend on order for either protocol`() {
        val awg = singBox(addresses = "\"fd00::2/128\",\"10.8.0.2/32\"").toActivationRequest()
        val wg =
            WireGuardIniSubscriptionParser
                .parse(
                    ini().replace("10.8.0.2/32, fd00::2/128", "fd00::2/128, 10.8.0.2/32"),
                    "group",
                ).profiles
                .single()
                .toActivationRequest()
        listOf(awg, wg).forEach { request ->
            assertEquals("10.8.0.2/32", request.interfaceAddressV4)
            assertEquals("fd00::2/128", request.interfaceAddressV6)
            request.requireRuntimeReady()
        }
    }

    @Test
    fun `explicit empty routes stay invalid instead of becoming default routes`() {
        val request = singBox("\"dns\":[],", "\"allowed_ips\":[],").toActivationRequest()
        assertEquals(emptyList<String>(), request.dnsServers)
        assertEquals(emptyList<String>(), request.allowedIps)
        assertTrue(runCatching { request.requireRuntimeReady() }.isFailure)
    }

    @Test
    fun `omitted policy keeps established defaults on first import`() {
        val request = singBox().toActivationRequest()
        assertEquals(emptyList<String>(), request.dnsServers)
        assertEquals(listOf("0.0.0.0/0"), request.allowedIps)
        request.requireRuntimeReady()
    }

    @Test
    fun `INI empty and omitted routes are distinct`() {
        val empty = ini().replace("AllowedIPs = 10.0.0.0/8, fd00::/8", "AllowedIPs =")
        val omitted = ini().replace("AllowedIPs = 10.0.0.0/8, fd00::/8", "# AllowedIPs = 10.0.0.0/8")
        val emptyRequest =
            WireGuardIniSubscriptionParser
                .parse(empty, "group")
                .profiles
                .single()
                .toActivationRequest()
        val omittedRequest =
            WireGuardIniSubscriptionParser
                .parse(
                    omitted,
                    "group",
                ).profiles
                .single()
                .toActivationRequest()
        assertEquals(emptyList<String>(), emptyRequest.allowedIps)
        assertEquals(listOf("0.0.0.0/0"), omittedRequest.allowedIps)
    }

    private fun singBox(
        interfacePolicy: String = "",
        peerPolicy: String = "",
        addresses: String = "\"10.8.0.2/32\",\"fd00::2/128\"",
    ) = (
        SingBoxSubscriptionParser.parse(
            """
            {"outbounds":[],"ripdpi":{"schema_version":1,"amneziawg":[{
              "tag":"network","private_key":"$Key","address":[$addresses],$interfacePolicy
              "peer":{$peerPolicy"public_key":"$Key","endpoint":"192.0.2.1:51820"}
            }]}}
            """.trimIndent(),
            "group",
        ) as SingBoxParseResult.Success
    ).amneziaWgProfiles.single()

    private fun ini(extra: String = "") =
        """
        [Interface]
        PrivateKey = $Key
        Address = 10.8.0.2/32, fd00::2/128
        DNS = 9.9.9.9, 2001:4860:4860::8888
        $extra
        [Peer]
        PublicKey = $Key
        Endpoint = 192.0.2.1:51820
        AllowedIPs = 10.0.0.0/8, fd00::/8
        """.trimIndent()

    private companion object {
        const val Key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
