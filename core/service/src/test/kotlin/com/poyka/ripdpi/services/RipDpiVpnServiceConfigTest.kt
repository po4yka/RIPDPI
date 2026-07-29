package com.poyka.ripdpi.services

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.poyka.ripdpi.core.defaultTun2SocksTunnelMtu
import com.poyka.ripdpi.core.routing.DestinationDomainMatcher
import com.poyka.ripdpi.core.routing.DestinationDomainMatcherKind
import com.poyka.ripdpi.core.routing.DestinationIpMatcher
import com.poyka.ripdpi.core.routing.DestinationIpMatcherKind
import com.poyka.ripdpi.core.routing.DestinationPortRange
import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.core.routing.DestinationRoutingNetwork
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.core.routing.DestinationRoutingRule
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.EncryptedDnsConfigInput
import com.poyka.ripdpi.data.EncryptedDnsOdohConfigSourceCustomBytes
import com.poyka.ripdpi.data.EncryptedDnsProtocolOdoh
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RipDpiVpnServiceConfigTest {
    private companion object {
        const val TestLocalProxyAuth = "alpha-123"
        const val TestRotatedLocalProxyAuth = "beta-456"

        val localProxyEndpoint =
            LocalProxyEndpoint(
                host = "127.0.0.1",
                port = 1080,
                username = VpnLocalProxyUsername,
                password = TestLocalProxyAuth,
            )
    }

    @Test
    fun tunnelNetworkPolicyClampsLowLinkMtuToIpv6SafeFloor() {
        val linkProperties = LinkProperties().apply { mtu = 1_260 }

        val parameters =
            VpnTunnelNetworkPolicy.parameters(
                linkProperties = linkProperties,
                capabilities = NetworkCapabilities(),
            )

        assertEquals(1_280, parameters.tunnelMtu)
        assertNull(parameters.appliedEncapsulationBudgetBytes)
    }

    @Test
    fun tunnelNetworkPolicyReportsEffectiveBudgetWhenFloorClampsReduction() {
        val linkProperties = LinkProperties().apply { mtu = 1_300 }

        val parameters =
            VpnTunnelNetworkPolicy.parameters(
                linkProperties = linkProperties,
                capabilities = NetworkCapabilities(),
            )

        assertEquals(1_280, parameters.tunnelMtu)
        assertEquals(20, parameters.appliedEncapsulationBudgetBytes)
    }

    @Test
    fun tunnelNetworkPolicyFallsBackToDefaultWhenLinkMtuIsUnknown() {
        val parameters =
            VpnTunnelNetworkPolicy.parameters(
                linkProperties = null,
                capabilities = NetworkCapabilities(),
            )

        assertEquals(defaultTun2SocksTunnelMtu, parameters.tunnelMtu)
        assertNull(parameters.appliedEncapsulationBudgetBytes)
    }

    @Test
    fun tunnelNetworkPolicyMirrorsMeteredCapability() {
        val unmetered =
            VpnTunnelNetworkPolicy.parameters(
                linkProperties = null,
                capabilities = networkCapabilitiesWith(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        val metered =
            VpnTunnelNetworkPolicy.parameters(
                linkProperties = null,
                capabilities = NetworkCapabilities(),
            )

        assertFalse(unmetered.metered)
        assertTrue(metered.metered)
    }

    @Test
    fun buildTun2SocksConfigIncludesIpv6TunnelAddressWhenEnabled() {
        val config =
            buildVpnTun2SocksConfig(
                dnsPlan = vpnTunnelDnsPlan(plainDns("2606:4700:4700::1111"), forceTunnelDns = false),
                overrideReason = null,
                localProxyEndpoint = localProxyEndpoint,
                ipv6Enabled = true,
                rootHelperSocketPath = "/data/user/0/com.poyka.ripdpi/files/root_helper.sock",
            )

        assertEquals("10.10.10.10/32", config.tunnelIpv4)
        assertEquals("fd00::1/128", config.tunnelIpv6)
        assertEquals(defaultTun2SocksTunnelMtu, config.tunnelMtu)
        assertEquals("udp", config.socks5Udp)
        assertEquals(1080, config.socks5Port)
        assertEquals("127.0.0.1", config.socks5Address)
        assertEquals(VpnLocalProxyUsername, config.username)
        assertEquals(TestLocalProxyAuth, config.password)
        assertEquals("/data/user/0/com.poyka.ripdpi/files/root_helper.sock", config.rootHelperSocketPath)
        assertNull(config.mapdnsAddress)
        assertTrue(
            RipDpiVpnService.vpnTunnelRoutePlan(ipv6Enabled = true).addresses.any {
                it.address == "fd00::1" && it.prefixLength == 128
            },
        )
        assertTrue(
            RipDpiVpnService.vpnTunnelRoutePlan(ipv6Enabled = true).routes.any {
                it.address == "::" && it.prefixLength == 0
            },
        )
    }

    @Test
    fun buildTun2SocksConfigCarriesNativeUidAdmissionPolicy() {
        val config =
            buildVpnTun2SocksConfig(
                dnsPlan = vpnTunnelDnsPlan(plainDns("1.1.1.1"), forceTunnelDns = false),
                overrideReason = null,
                localProxyEndpoint = localProxyEndpoint,
                ipv6Enabled = false,
                geositeDbPath = "/data/user/0/com.example/files/geo/geosite.db",
                uidPolicy = NativeUidPolicy("allowlist", listOf(10123, 10124)),
            )

        assertEquals("allowlist", config.uidPolicyMode)
        assertEquals(listOf(10123, 10124), config.uidPolicyUids)
        assertFalse(config.uidPolicyAllowIcmp)
    }

    @Test
    fun buildTun2SocksConfigCarriesNativeUidIcmpOptIn() {
        val config =
            buildVpnTun2SocksConfig(
                dnsPlan = vpnTunnelDnsPlan(plainDns("1.1.1.1"), forceTunnelDns = false),
                overrideReason = null,
                localProxyEndpoint = localProxyEndpoint,
                ipv6Enabled = false,
                uidPolicy = NativeUidPolicy("allowlist", listOf(10123)),
                uidPolicyAllowIcmp = true,
            )

        assertTrue(config.uidPolicyAllowIcmp)
    }

    @Test
    fun buildTun2SocksConfigCarriesWebRtcProtection() {
        val config =
            buildVpnTun2SocksConfig(
                dnsPlan = vpnTunnelDnsPlan(plainDns("1.1.1.1"), forceTunnelDns = false),
                overrideReason = null,
                localProxyEndpoint = localProxyEndpoint,
                ipv6Enabled = false,
                webrtcProtectionEnabled = true,
            )

        assertTrue(config.webrtcProtectionEnabled)
    }

    @Test
    fun buildTun2SocksConfigUsesMapDnsAndLeavesIpv6UnsetWhenDisabled() {
        val tlsRootsPem = "-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----"
        val config =
            buildVpnTun2SocksConfig(
                dnsPlan = vpnTunnelDnsPlan(encryptedDns(), forceTunnelDns = false),
                overrideReason = "dns_probe_failed",
                localProxyEndpoint =
                    localProxyEndpoint.copy(port = 2080, password = TestRotatedLocalProxyAuth),
                ipv6Enabled = false,
                encryptedDnsTlsRootsPem = tlsRootsPem,
            )

        assertEquals("10.10.10.10/32", config.tunnelIpv4)
        assertNull(config.tunnelIpv6)
        assertEquals(defaultTun2SocksTunnelMtu, config.tunnelMtu)
        assertEquals("udp", config.socks5Udp)
        assertEquals(2080, config.socks5Port)
        assertEquals("198.18.0.53", config.mapdnsAddress)
        assertEquals(53, config.mapdnsPort)
        assertEquals("198.18.0.0", config.mapdnsNetwork)
        assertEquals("255.254.0.0", config.mapdnsNetmask)
        assertEquals("cloudflare", config.encryptedDnsResolverId)
        assertEquals(tlsRootsPem, config.encryptedDnsTlsRootsPem)
        assertTrue(config.resolverFallbackActive == true)
        assertEquals("dns_probe_failed", config.resolverFallbackReason)
        assertEquals(TestRotatedLocalProxyAuth, config.password)
        val routePlan = RipDpiVpnService.vpnTunnelRoutePlan(ipv6Enabled = false)
        assertTrue(routePlan.addresses.none { it.address.contains(":") })
        assertTrue(routePlan.routes.none { it.address == "::" })
    }

    @Test
    fun buildTun2SocksConfigRoutesPlainDnsThroughMapDnsWhenRelayDnsIsActive() {
        val defaultEncryptedDns = canonicalDefaultEncryptedDnsSettings()
        val config =
            buildVpnTun2SocksConfig(
                dnsPlan = vpnTunnelDnsPlan(plainDns("8.8.8.8"), forceTunnelDns = true),
                overrideReason = null,
                localProxyEndpoint = localProxyEndpoint.copy(port = 2080),
                ipv6Enabled = false,
            )

        assertEquals("198.18.0.53", config.mapdnsAddress)
        assertEquals(53, config.mapdnsPort)
        assertEquals(defaultEncryptedDns.providerId, config.encryptedDnsResolverId)
        assertEquals(defaultEncryptedDns.encryptedDnsProtocol, config.encryptedDnsProtocol)
        assertEquals(defaultEncryptedDns.encryptedDnsHost, config.encryptedDnsHost)
        assertEquals(defaultEncryptedDns.encryptedDnsPort, config.encryptedDnsPort)
        assertEquals(defaultEncryptedDns.encryptedDnsDohUrl, config.encryptedDnsDohUrl)
        assertTrue(config.routeDnsThroughSocks5 == true)
    }

    @Test
    fun buildTun2SocksConfigPreservesOdohConfigAndRoutesProxyLegThroughRelay() {
        val config =
            buildVpnTun2SocksConfig(
                dnsPlan = vpnTunnelDnsPlan(odohDns(), forceTunnelDns = true),
                overrideReason = null,
                localProxyEndpoint = localProxyEndpoint.copy(port = 2080),
                ipv6Enabled = false,
            )

        assertEquals(EncryptedDnsProtocolOdoh, config.encryptedDnsProtocol)
        assertEquals("proxy.example.test", config.encryptedDnsHost)
        assertEquals(443, config.encryptedDnsPort)
        assertEquals("https://proxy.example.test/proxy", config.encryptedDnsOdohProxyUrl)
        assertEquals("ProxyNet", config.encryptedDnsOdohProxyOperatorId)
        assertEquals("target.example.test", config.encryptedDnsOdohTargetHost)
        assertEquals("/dns-query", config.encryptedDnsOdohTargetPath)
        assertEquals("TargetNet", config.encryptedDnsOdohTargetOperatorId)
        assertEquals(EncryptedDnsOdohConfigSourceCustomBytes, config.encryptedDnsOdohConfigSource)
        assertEquals("00aa", config.encryptedDnsOdohConfigsHex)
        assertEquals(1_700_000_000L, config.encryptedDnsOdohConfigsRetrievedAtSecs)
        assertEquals(86_400L, config.encryptedDnsOdohConfigsTtlSecs)
        assertTrue(config.routeDnsThroughSocks5 == true)
    }

    @Test
    fun buildTun2SocksConfigSerializesValidatedSplitDnsPolicyInCanonicalOrder() {
        val destinationRouting =
            DestinationRoutingPolicy(
                rules =
                    listOf(
                        DestinationRoutingRule(
                            action = DestinationRoutingAction.DIRECT,
                            network = DestinationRoutingNetwork.BOTH,
                            domains =
                                listOf(
                                    DestinationDomainMatcher(DestinationDomainMatcherKind.EXACT, "api.example"),
                                    DestinationDomainMatcher(DestinationDomainMatcherKind.SUFFIX, "example"),
                                ),
                        ),
                        DestinationRoutingRule(
                            action = DestinationRoutingAction.BLOCK,
                            network = DestinationRoutingNetwork.UDP,
                            domains = listOf(DestinationDomainMatcher(DestinationDomainMatcherKind.GEOSITE, "private")),
                            ipRanges = listOf(DestinationIpMatcher(DestinationIpMatcherKind.CIDR, "192.0.2.0/24")),
                            destinationPorts = listOf(DestinationPortRange(53, 53)),
                        ),
                    ),
                canonicalDigest = "b".repeat(64),
            )
        val activeDns = encryptedDns().copy(encryptedDnsBootstrapIps = listOf("94.140.14.14", "2001:db8::53"))
        val splitPolicy =
            ValidatedSplitStrictDnsPolicy.build(
                activeDns = activeDns,
                routingSnapshot = DestinationRoutingPolicySnapshot.Available(destinationRouting),
                underlayDnsServers = listOf("192.0.2.53", "2001:db8::54"),
            )

        val config =
            buildVpnTun2SocksConfig(
                dnsPlan = vpnTunnelDnsPlan(activeDns, forceTunnelDns = false, splitStrictPolicy = splitPolicy),
                overrideReason = null,
                localProxyEndpoint = localProxyEndpoint,
                ipv6Enabled = false,
                geositeDbPath = "/data/user/0/com.example/files/geo/geosite.db",
            )

        val native = requireNotNull(config.splitDnsPolicy)
        assertEquals(splitPolicy.canonicalDigest, native.canonicalDigest)
        assertEquals("b".repeat(64), native.destinationRoutingDigest)
        assertEquals("tunneled", native.defaultAction)
        assertEquals(listOf("192.0.2.53", "2001:db8:0:0:0:0:0:54"), native.directResolverCandidates)
        assertEquals(listOf("94.140.14.14", "2001:db8:0:0:0:0:0:53"), native.bootstrapPins)
        assertEquals("/data/user/0/com.example/files/geo/geosite.db", native.geositeDbPath)
        assertNull(native.coverageReason)
        assertEquals(listOf("exact", "suffix"), native.rules[0].domains.map { it.kind })
        assertEquals(listOf("api.example", "example"), native.rules[0].domains.map { it.value })
        assertEquals("direct", native.rules[0].action)
        assertEquals("both", native.rules[0].network)
        assertFalse(native.rules[0].hasIpRanges)
        assertFalse(native.rules[0].hasPorts)
        assertEquals("block", native.rules[1].action)
        assertEquals("udp", native.rules[1].network)
        assertTrue(native.rules[1].hasIpRanges)
        assertTrue(native.rules[1].hasPorts)
    }

    @Test
    fun buildTun2SocksConfigSerializesSplitDnsCoverageReasonAndPlainNull() {
        val unavailable =
            ValidatedSplitStrictDnsPolicy.build(
                activeDns = encryptedDns(),
                routingSnapshot = DestinationRoutingPolicySnapshot.Unavailable("source_unavailable"),
                underlayDnsServers = listOf("192.0.2.53"),
            )
        val encryptedConfig =
            buildVpnTun2SocksConfig(
                dnsPlan = vpnTunnelDnsPlan(encryptedDns(), forceTunnelDns = false, splitStrictPolicy = unavailable),
                overrideReason = null,
                localProxyEndpoint = localProxyEndpoint,
                ipv6Enabled = false,
            )
        assertEquals("route_policy_unavailable:source_unavailable", encryptedConfig.splitDnsPolicy?.coverageReason)

        val plainConfig =
            buildVpnTun2SocksConfig(
                dnsPlan = vpnTunnelDnsPlan(plainDns("1.1.1.1"), forceTunnelDns = false),
                overrideReason = null,
                localProxyEndpoint = localProxyEndpoint,
                ipv6Enabled = false,
            )
        assertNull(plainConfig.splitDnsPolicy)
        val encoded =
            Json {
                encodeDefaults = true
                explicitNulls = true
            }.encodeToString(plainConfig)
        assertTrue(encoded.contains("\"splitDnsPolicy\":null"))
    }

    @Test
    fun vpnTunnelRoutePlanKeepsIpv4DefaultsWhenIpv6IsDisabled() {
        val routePlan = RipDpiVpnService.vpnTunnelRoutePlan(ipv6Enabled = false)

        assertEquals(listOf(VpnTunnelRouteEntry("10.10.10.10", 32)), routePlan.addresses)
        assertEquals(listOf(VpnTunnelRouteEntry("0.0.0.0", 0)), routePlan.routes)
    }

    @Test
    fun vpnTunnelRoutePlanAddsIpv6AddressAndDefaultRouteWhenIpv6IsEnabled() {
        val routePlan = RipDpiVpnService.vpnTunnelRoutePlan(ipv6Enabled = true)

        assertEquals(
            listOf(
                VpnTunnelRouteEntry("10.10.10.10", 32),
                VpnTunnelRouteEntry("fd00::1", 128),
            ),
            routePlan.addresses,
        )
        assertEquals(
            listOf(
                VpnTunnelRouteEntry("0.0.0.0", 0),
                VpnTunnelRouteEntry("::", 0),
            ),
            routePlan.routes,
        )
    }

    private fun plainDns(dnsIp: String): ActiveDnsSettings =
        ActiveDnsSettings(
            mode = DnsModePlainUdp,
            providerId = "custom",
            dnsIp = dnsIp,
            encryptedDnsProtocol = "",
            encryptedDnsHost = "",
            encryptedDnsPort = 0,
            encryptedDnsTlsServerName = "",
            encryptedDnsBootstrapIps = emptyList(),
            encryptedDnsDohUrl = "",
            encryptedDnsDnscryptProviderName = "",
            encryptedDnsDnscryptPublicKey = "",
        )

    private fun encryptedDns(): ActiveDnsSettings =
        ActiveDnsSettings(
            mode = DnsModeEncrypted,
            providerId = "cloudflare",
            dnsIp = "1.1.1.1",
            encryptedDnsProtocol = "doh",
            encryptedDnsHost = "cloudflare-dns.com",
            encryptedDnsPort = 443,
            encryptedDnsTlsServerName = "cloudflare-dns.com",
            encryptedDnsBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
            encryptedDnsDohUrl = "https://cloudflare-dns.com/dns-query",
            encryptedDnsDnscryptProviderName = "",
            encryptedDnsDnscryptPublicKey = "",
        )

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun buildHttpProxyInfoSetsHostPortAndExclusionListOnQ() {
        val proxy = RipDpiVpnService.buildHttpProxyInfo(2080)

        assertEquals("127.0.0.1", proxy.host)
        assertEquals(2080, proxy.port)
        assertEquals(RipDpiVpnService.httpProxyExclusionList, proxy.exclusionList?.toList())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun buildHttpProxyInfoRespectsDifferentPorts() {
        val proxy1080 = RipDpiVpnService.buildHttpProxyInfo(1080)
        val proxy8080 = RipDpiVpnService.buildHttpProxyInfo(8080)

        assertEquals(1080, proxy1080.port)
        assertEquals(8080, proxy8080.port)
    }

    private fun odohDns(): ActiveDnsSettings =
        activeDnsSettings(
            dnsMode = DnsModeEncrypted,
            dnsProviderId = "custom",
            dnsIp = "",
            encryptedDns =
                EncryptedDnsConfigInput(
                    protocol = EncryptedDnsProtocolOdoh,
                    bootstrapIps = listOf("203.0.113.10"),
                    odohProxyUrl = "https://proxy.example.test/proxy",
                    odohProxyOperatorId = "ProxyNet",
                    odohTargetHost = "target.example.test",
                    odohTargetPath = "/dns-query",
                    odohTargetOperatorId = "TargetNet",
                    odohConfigSource = EncryptedDnsOdohConfigSourceCustomBytes,
                    odohConfigsHex = "00aa",
                    odohConfigsRetrievedAtSecs = 1_700_000_000L,
                    odohConfigsTtlSecs = 86_400L,
                ),
        )

    private fun networkCapabilitiesWith(capability: Int): NetworkCapabilities =
        NetworkCapabilities().also { capabilities ->
            NetworkCapabilities::class
                .java
                .getDeclaredMethod("addCapability", Int::class.javaPrimitiveType)
                .invoke(capabilities, capability)
        }
}
