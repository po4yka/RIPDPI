package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRelayKindResolverTest {
    private val resolver = DefaultRelayKindResolver()

    private fun request(config: RipDpiRelayConfig): RelayResolverRequest =
        RelayResolverRequest(
            profileId = config.profileId,
            mergedConfig = config,
            credentials =
                RelayCredentialRecord(
                    profileId = config.profileId,
                    vlessUuid = "00000000-0000-0000-0000-000000000000",
                ),
            requestedTlsProfile = TlsFingerprintProfileChromeStable,
            featureFlags = emptyMap(),
        )

    @Test
    fun `default resolver claims both vless reality and plain vless kinds`() {
        // The default resolver is the catch-all native VLESS path; the
        // dedicated resolvers must not steal the vless kinds.
        assertTrue(resolver.supports(RelayKindVlessReality))
        assertTrue(resolver.supports(RelayKindVless))
    }

    @Test
    fun `resolve routes vless reality tcp through the native vless path`() =
        runTest {
            val config =
                RipDpiRelayConfig(
                    enabled = true,
                    kind = RelayKindVlessReality,
                    profileId = "p0",
                    server = "relay.example.com",
                    serverPort = 443,
                    serverName = "relay.example.com",
                    realityPublicKey = "public-key",
                    realityShortId = "short-id",
                    vlessTransport = RelayVlessTransportRealityTcp,
                )

            val result = resolver.resolve(request(config))

            assertEquals(RelayKindVlessReality, result.effectiveConfig.kind)
            assertEquals(RelayVlessTransportRealityTcp, result.effectiveConfig.vlessTransport)
        }

    @Test
    fun `resolve routes plain tls vless xhttp through the native vless path`() =
        runTest {
            val config =
                RipDpiRelayConfig(
                    enabled = true,
                    kind = RelayKindVless,
                    profileId = "p1",
                    server = "edge.example.com",
                    serverPort = 8443,
                    serverName = "edge.example.com",
                    vlessTransport = RelayVlessTransportXhttp,
                    xhttpPath = "/xhttp",
                    xhttpHost = "origin.example.com",
                )

            val result = resolver.resolve(request(config))

            assertEquals(RelayKindVless, result.effectiveConfig.kind)
            assertEquals(RelayVlessTransportXhttp, result.effectiveConfig.vlessTransport)
            assertEquals("/xhttp", result.effectiveConfig.xhttpPath)
            assertEquals("origin.example.com", result.effectiveConfig.xhttpHost)
            assertTrue(result.effectiveConfig.realityPublicKey.isEmpty())
        }

    @Test
    fun `dedicated resolvers do not claim the plain vless kind`() {
        assertFalse(CloudflareTunnelRelayKindResolver().supports(RelayKindVless))
        assertFalse(SnowflakeRelayKindResolver().supports(RelayKindVless))
        assertFalse(NaiveRelayKindResolver().supports(RelayKindVless))
        assertFalse(LocalPathRelayKindResolver().supports(RelayKindVless))
        // sanity: they still claim their own kinds
        assertTrue(CloudflareTunnelRelayKindResolver().supports(RelayKindCloudflareTunnel))
    }
}
